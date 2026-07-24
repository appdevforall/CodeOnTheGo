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
 * The overlay is ERROR-MOSTLY (plan A1, WS-G): a CoGo-side build failure ({@link #handleBuildStatus}) or a payload crash shows a banner saying the app still runs the last working version, cleared by the next successful build/reload; a one-time hint for the 3-finger return gesture; and (WS-G) a neutral in-flight line while a build compiles, so a slow build never reads as silence. Success itself still renders nothing - it only clears whatever was showing.
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

	private static ParcelFileDescriptor openReadOnly(File file) throws IOException {
		return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
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

	private static byte[] readBytesAndClose(ParcelFileDescriptor fd) throws IOException {
		if (fd == null) {
			return null;
		}
		InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(fd);
		try {
			return Streams.readFully(in);
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
	private final ReturnToIdeButton returnButton = new ReturnToIdeButton();

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
			} else if (BuildStatus.KIND_BUILDING.equals(status.kind)) {
				// Replaces whatever was showing (a stale failure, the gesture hint, or
				// nothing) - a new attempt starting is real news either way.
				setOverlayState(OverlayState.building(status.runningGeneration));
			} else if (overlayState.isError() || overlayState.isBuilding()) {
				// build_ok clears a stale failure or in-flight banner; it never renders
				// anything itself.
				setOverlayState(OverlayState.hidden());
			}
		} catch (Throwable error) {
			RuntimeLog.w("unusable build status; dropped", error);
		}
	}

	/**
	 * Applies one deploy. Runs on a binder thread; heavy work (fd reads, persistence, zip extraction) happens here, only the reload itself is posted to the main thread.
	 *
	 * Every accepted payload is persisted BEFORE it is applied ({@link PayloadPersistence}) so a killed-and-relaunched process boots the newest generation, and a restart deploy ({@code "restart": "true"} metadata) persists + acks + exits instead of hot-swapping - the component-proxying contract, section 4. A persist failure fails the deploy loudly (rollback + reportCrash): proceeding would leave a boot path that silently serves older code.
	 */
	void handlePayload(long generation, ParcelFileDescriptor dexPayload,
			ParcelFileDescriptor resourcesPayload, ParcelFileDescriptor assetsPayload,
			String metadataJson) {
		long startUptime = SystemClock.uptimeMillis();
		PayloadStore.Payload previous = PayloadStore.INSTANCE.snapshot();
		try {
			DeployMetadata metadata = parseMetadata(metadataJson);
			byte[] dexBytes = readBytesAndClose(dexPayload);
			byte[] arscBytes = readBytesAndClose(resourcesPayload);
			byte[] assetsBytes = readBytesAndClose(assetsPayload);
			if (previous == null
					|| !Generations.accepts(previous.generation, generation)) {
				// Stale or baseline-less: contract says drop it. No report - claiming a
				// reload for a payload we refused would be a lie the host acts on.
				RuntimeLog.w("dropping payload gen " + generation + " (running "
						+ (previous == null ? "no baseline" : "gen " + previous.generation) + ")");
				return;
			}
			if (metadata.restart && dexBytes == null) {
				// A restart deploy exists to move component CODE; without a dex the
				// relaunch would boot old classes under a new generation label - a
				// stale-code lie. Refuse loudly (a CoGo bug if it ever happens).
				throw new IllegalStateException("restart deploy without a dex payload");
			}
			PayloadPersistence.Persisted persisted = persistPayload(generation, dexBytes, arscBytes, assetsBytes);
			if (metadata.restart) {
				// Restart deploy: the payload is on disk; ack, then exit. The fresh
				// process boots the persisted generation, which is what makes the swap
				// real for services/providers/the Application. Never applied in-memory -
				// this process is already condemned.
				RuntimeLog.i("restart deploy gen " + generation + " persisted; exiting");
				client.reportReloaded(generation, SystemClock.uptimeMillis() - startUptime);
				exitForRestart();
				return;
			}
			if (!PayloadStore.INSTANCE.apply(generation,
					dexBytes == null ? null : ByteBuffer.wrap(dexBytes))) {
				// Raced by a newer payload between the acceptance check and here.
				return;
			}
			if (arscBytes != null) {
				ResourceStore.INSTANCE.applyTable(
						openReadOnly(persisted.arscFile), generation, application);
			}
			if (assetsBytes != null) {
				ResourceStore.INSTANCE.applyAssets(
						openReadOnly(persisted.assetsFile), generation,
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
		PayloadStore.INSTANCE.attachPersistence(activity.getApplicationContext());
		applyPendingBootResources(activity.getApplicationContext());
	}

	void onActivityResumed(Activity activity) {
		long pending = pendingReloadGeneration;
		if (pending >= 0 && PayloadStore.INSTANCE.generation() == pending) {
			pendingReloadGeneration = -1;
			long reloadMillis = SystemClock.uptimeMillis() - pendingReloadStartUptime;
			client.reportReloaded(pending, reloadMillis);
			// Success renders nothing itself - it only CLEARS a shown error or an
			// in-flight banner (a reload landing IS the build finishing, even if the
			// build_ok status message is still in flight behind it).
			if (overlayState.isError() || overlayState.isBuilding()) {
				setOverlayState(OverlayState.hidden());
			} else {
				overlay.render(activity, overlayState);
			}
		} else {
			maybeShowGestureHint(activity);
			overlay.render(activity, overlayState);
		}
		returnButton.render(activity);
	}

	long runningGeneration() {
		return PayloadStore.INSTANCE.generation();
	}

	/**
	 * Restores persisted resource payloads found at boot (the code half already loaded pre-Context in {@link PayloadStore#ensureBaseline}). Runs at the first activity - the first moment a Context exists; components that read resources earlier (providers) see baseline resources until here, a documented residual of the persisted-boot contract. Best-effort: a failure keeps baseline resources, and the next deploy re-applies current ones.
	 */
	private void applyPendingBootResources(android.content.Context context) {
		PayloadPersistence.Loaded pending = PayloadStore.INSTANCE.takePendingBootResources();
		if (pending == null) {
			return;
		}
		try {
			if (pending.arscFile != null) {
				ResourceStore.INSTANCE.applyTable(
						openReadOnly(pending.arscFile), pending.generation, context);
			}
			if (pending.assetsFile != null) {
				ResourceStore.INSTANCE.applyAssets(
						openReadOnly(pending.assetsFile), pending.generation,
						context.getCacheDir());
			}
			RuntimeLog.i("restored persisted resources for gen " + pending.generation);
		} catch (Throwable error) {
			RuntimeLog.e("could not restore persisted resources", error);
		}
	}

	/** The process must actually die - a restart deploy's ack promises a fresh boot. */
	private void exitForRestart() {
		android.os.Process.killProcess(android.os.Process.myPid());
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
	 * Persists the payload before anything applies (see {@link #handlePayload}). Throws when the store is unavailable or the write fails - the deploy then fails loudly instead of leaving the boot path behind the running generation.
	 */
	private PayloadPersistence.Persisted persistPayload(long generation, byte[] dex,
			byte[] arsc, byte[] assetsZip) throws IOException {
		PayloadPersistence store = PayloadStore.INSTANCE.persistence();
		String fingerprint = PayloadStore.INSTANCE.baselineFingerprint();
		if (store == null || fingerprint == null) {
			throw new IOException("payload persistence unavailable");
		}
		return store.persist(generation, fingerprint, dex, arsc, assetsZip);
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
