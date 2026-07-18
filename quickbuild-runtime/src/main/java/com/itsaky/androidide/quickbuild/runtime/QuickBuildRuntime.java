package com.itsaky.androidide.quickbuild.runtime;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

/**
 * The conductor of the test-app runtime: receives payloads from {@link QuickBuildClient}, applies them to {@link PayloadStore}/{@link ResourceStore}, drives the reload, and keeps the {@link StatusOverlay} and the host's reports honest.
 *
 * Installed once per process by {@link QuickBuildAppComponentFactory} at application instantiation - the earliest hook a library gets without a ContentProvider. Context work (binding to CoGo, cache dirs) is deferred to the first activity because the Application has no base context yet at install time.
 *
 * The overlay is ERROR-ONLY (plan A1): nothing renders on success or while building; a CoGo-side build failure ({@link #handleBuildStatus}) or a payload crash shows a banner saying the app still runs the last working version, cleared by the next successful build/reload. Plus a one-time hint for the 3-finger return gesture.
 *
 * Failure policy, everywhere: a reload failure calls reportCrash and ROLLS BACK to the old generation - the app keeps running gen N and says so; it never crash-loops on a bad payload and never silently claims gen N+1 (the never-stale invariant, plan 1.4).
 */
final class QuickBuildRuntime {

	static final String COMPONENT_MAP_ASSET = "assets/quickbuild/components.json";

	/** Marker file (in filesDir) recording that the gesture hint was already shown. */
	static final String HINT_SHOWN_FILE = "quickbuild-gesture-hint-shown";

	private static final int HINT_HIDE_MS = 6000;
	private static final int MAX_CRASH_SUMMARY_FRAMES = 5;
	private static final int MAX_CRASH_SUMMARY_LENGTH = 2000;

	private static volatile QuickBuildRuntime instance;

	/** Installs the runtime once; safe to call repeatedly. Never throws. */
	static void install(Application application) {
		if (instance != null || application == null) {
			return;
		}
		synchronized (QuickBuildRuntime.class) {
			if (instance != null) {
				return;
			}
			try {
				QuickBuildRuntime runtime = new QuickBuildRuntime(application);
				runtime.start();
				instance = runtime;
			} catch (Throwable error) {
				RuntimeLog.e("failed to install quick build runtime", error);
			}
		}
	}

	private static DeployMetadata parseMetadata(String metadataJson) {
		try {
			return DeployMetadata.parse(metadataJson);
		} catch (IllegalArgumentException error) {
			// A bad metadata blob must not block a code reload; fall back to defaults
			// (recreate-only, no entry launch) and log loudly.
			RuntimeLog.e("unparseable deploy metadata; using defaults", error);
			return new DeployMetadata(null, null, null);
		}
	}

	private static ByteBuffer readFullyAndClose(ParcelFileDescriptor fd) throws IOException {
		if (fd == null) {
			return null;
		}
		InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(fd);
		try {
			return ByteBuffer.wrap(Streams.readFully(in));
		} finally {
			in.close();
		}
	}

	/** Compact single-string stack summary for reportCrash / the overlay. */
	private static String summarize(Throwable error) {
		StringBuilder sb = new StringBuilder();
		sb.append(error.toString());
		StackTraceElement[] frames = error.getStackTrace();
		int limit = Math.min(frames.length, MAX_CRASH_SUMMARY_FRAMES);
		for (int i = 0; i < limit; i++) {
			sb.append("\n at ").append(frames[i]);
		}
		Throwable cause = error.getCause();
		if (cause != null && cause != error) {
			sb.append("\nCaused by: ").append(cause.toString());
		}
		if (sb.length() > MAX_CRASH_SUMMARY_LENGTH) {
			sb.setLength(MAX_CRASH_SUMMARY_LENGTH);
		}
		return sb.toString();
	}

	private final Application application;
	private final Handler mainHandler = new Handler(Looper.getMainLooper());

	private final ActivityTracker tracker = new ActivityTracker(this);
	private final QuickBuildClient client = new QuickBuildClient(this);

	private final StatusOverlay overlay = new StatusOverlay();
	private volatile ComponentMap componentMap = ComponentMap.EMPTY;

	private volatile OverlayState overlayState = OverlayState.hidden();

	/** Generation whose reload is awaiting its first resumed frame, or -1. */
	private volatile long pendingReloadGeneration = -1;

	private volatile long pendingReloadStartUptime;

	private QuickBuildRuntime(Application application) {
		this.application = application;
	}

	/**
	 * Build-status message from CoGo (plan A1) - the only way the running app learns about a compile error, which never produces a payload. Runs on a binder thread; guards all throwables so nothing escapes into the binder.
	 */
	void handleBuildStatus(String statusJson) {
		try {
			BuildStatus status = BuildStatus.parse(statusJson);
			if (status == null) {
				// Unknown kind from a newer CoGo: the versioning contract says ignore.
				return;
			}
			if (BuildStatus.KIND_BUILD_FAILED.equals(status.kind)) {
				setOverlayState(OverlayState.buildFailed(status));
			} else if (overlayState.isError()) {
				// build_ok clears a stale failure banner; it never renders anything.
				setOverlayState(OverlayState.hidden());
			}
		} catch (Throwable error) {
			RuntimeLog.w("unusable build status; dropped", error);
		}
	}

	/**
	 * Applies one deploy. Runs on a binder thread; heavy work (fd reads, zip extraction) happens here, only the reload itself is posted to the main thread.
	 */
	void handlePayload(long generation, ParcelFileDescriptor dexPayload,
			ParcelFileDescriptor resourcesPayload, ParcelFileDescriptor assetsPayload,
			String metadataJson) {
		long startUptime = SystemClock.uptimeMillis();
		PayloadStore.Payload previous = PayloadStore.INSTANCE.snapshot();
		try {
			DeployMetadata metadata = parseMetadata(metadataJson);
			ByteBuffer dex = readFullyAndClose(dexPayload);
			if (!PayloadStore.INSTANCE.apply(generation, dex)) {
				// Stale or baseline-less: contract says drop it. No report - claiming a
				// reload for a payload we refused would be a lie the host acts on.
				Streams.closeQuietly(resourcesPayload);
				Streams.closeQuietly(assetsPayload);
				return;
			}
			if (resourcesPayload != null) {
				ResourceStore.INSTANCE.applyTable(resourcesPayload, generation, application);
			}
			if (assetsPayload != null) {
				ResourceStore.INSTANCE.applyAssets(assetsPayload, generation,
						application.getCacheDir());
			}
			pendingReloadStartUptime = startUptime;
			pendingReloadGeneration = generation;
			final long reloadGeneration = generation;
			final DeployMetadata reloadMetadata = metadata;
			final PayloadStore.Payload rollback = previous;
			mainHandler.post(new Runnable() {

				@Override
				public void run() {
					reloadOnMain(reloadGeneration, reloadMetadata, rollback);
				}
			});
		} catch (Throwable error) {
			RuntimeLog.e("payload gen " + generation + " failed to apply", error);
			Streams.closeQuietly(dexPayload);
			Streams.closeQuietly(resourcesPayload);
			Streams.closeQuietly(assetsPayload);
			failReload(generation, previous, error);
		}
	}

	void onActivityCreated(Activity activity) {
		// First moment a usable Context exists; bind() is idempotent.
		client.bind(activity.getApplicationContext());
	}

	void onActivityResumed(Activity activity) {
		long pending = pendingReloadGeneration;
		if (pending >= 0 && PayloadStore.INSTANCE.generation() == pending) {
			pendingReloadGeneration = -1;
			long reloadMillis = SystemClock.uptimeMillis() - pendingReloadStartUptime;
			client.reportReloaded(pending, reloadMillis);
			// ERROR-ONLY overlay: success renders nothing, it only CLEARS a shown error.
			if (overlayState.isError()) {
				setOverlayState(OverlayState.hidden());
			} else {
				overlay.render(activity, overlayState);
			}
		} else {
			maybeShowGestureHint(activity);
			overlay.render(activity, overlayState);
		}
	}

	long runningGeneration() {
		return PayloadStore.INSTANCE.generation();
	}

	/** Roll back, tell the host, show the honesty banner. The app stays on the old gen. */
	private void failReload(long generation, PayloadStore.Payload rollback, Throwable error) {
		PayloadStore.INSTANCE.restore(rollback);
		pendingReloadGeneration = -1;
		String summary = summarize(error);
		setOverlayState(OverlayState.crashed(summary));
		client.reportCrash(generation, summary);
	}

	/**
	 * A payload crash during render happens OUTSIDE our call stack (the recreated activity throws in its own lifecycle), so the only interception point is the default uncaught handler. Report to the host best-effort, then delegate - the process still dies, but CoGo learns WHY, and on relaunch the app starts from the baseline and reconnects with its (old) running generation so the host can decide what to redeploy.
	 */
	private void installCrashGuard() {
		final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
		Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {

			@Override
			public void uncaughtException(Thread thread, Throwable error) {
				try {
					long pending = pendingReloadGeneration;
					if (pending >= 0) {
						client.reportCrash(pending, summarize(error));
					}
				} catch (Throwable ignored) {
					// The crash guard itself must never throw.
				}
				if (previous != null) {
					previous.uncaughtException(thread, error);
				}
			}
		});
	}

	private void launchEntryActivity(DeployMetadata metadata) {
		String entry = metadata.entryActivity;
		if (entry == null) {
			throw new IllegalStateException(
					"no live activity to recreate and no entryActivity in deploy metadata");
		}
		// The manifest only knows the proxies; translate. Falling back to the raw name
		// covers a host that already sends the proxy class.
		String component = componentMap.proxyFor(entry);
		if (component == null) {
			component = entry;
		}
		Intent intent = new Intent();
		intent.setClassName(application.getPackageName(), component);
		intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		application.startActivity(intent);
	}

	private void loadComponentMap() {
		ClassLoader apkLoader = PayloadStore.INSTANCE.apkClassLoader();
		if (apkLoader == null) {
			return;
		}
		InputStream in = null;
		try {
			in = apkLoader.getResourceAsStream(COMPONENT_MAP_ASSET);
			if (in == null) {
				RuntimeLog.w("no component map at " + COMPONENT_MAP_ASSET);
				return;
			}
			componentMap = ComponentMap.parse(new String(Streams.readFully(in), "UTF-8"));
			RuntimeLog.i("component map loaded (" + componentMap.size() + " entries)");
		} catch (Throwable error) {
			RuntimeLog.e("failed to load component map", error);
		} finally {
			Streams.closeQuietly(in);
		}
	}

	/**
	 * One-time discoverability hint for the 3-finger return gesture (plan A3). Shown on the first resume of the first session after install, only when nothing else is on the overlay; a marker file in filesDir keeps it from ever showing again.
	 */
	private void maybeShowGestureHint(Activity activity) {
		if (overlayState.kind != OverlayState.Kind.HIDDEN) {
			return;
		}
		try {
			File marker = new File(activity.getFilesDir(), HINT_SHOWN_FILE);
			if (marker.exists() || !marker.createNewFile()) {
				return;
			}
			setOverlayState(OverlayState.hint());
			scheduleAutoHide(overlayState, HINT_HIDE_MS);
		} catch (Throwable error) {
			RuntimeLog.w("gesture hint skipped", error);
		}
	}

	/**
	 * Recreates the top activity so it re-instantiates from the new generation's classloader - that recreation is what makes the reload real. With no live activity, launches the entry activity through its manifest-stable proxy.
	 */
	private void reloadOnMain(long generation, DeployMetadata metadata,
			PayloadStore.Payload rollback) {
		try {
			Activity top = tracker.topActivity();
			if (top != null) {
				top.recreate();
			} else {
				launchEntryActivity(metadata);
			}
			// reportReloaded fires from onActivityResumed, after the reload rendered.
		} catch (Throwable error) {
			RuntimeLog.e("reload for gen " + generation + " failed", error);
			failReload(generation, rollback, error);
		}
	}

	/** Auto-hide a transient banner - but only if that exact state is still current. */
	private void scheduleAutoHide(final OverlayState shown, int delayMillis) {
		mainHandler.postDelayed(new Runnable() {

			@Override
			public void run() {
				if (overlayState == shown) {
					setOverlayState(OverlayState.hidden());
				}
			}
		}, delayMillis);
	}

	private void setOverlayState(OverlayState state) {
		overlayState = state;
		mainHandler.post(new Runnable() {

			@Override
			public void run() {
				overlay.render(tracker.topActivity(), overlayState);
			}
		});
	}

	private void start() {
		application.registerActivityLifecycleCallbacks(tracker);
		loadComponentMap();
		installCrashGuard();
	}
}
