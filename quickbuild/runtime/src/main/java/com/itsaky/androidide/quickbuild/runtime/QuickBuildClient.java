package com.itsaky.androidide.quickbuild.runtime;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import com.itsaky.androidide.quickbuild.IQuickBuildHost;
import com.itsaky.androidide.quickbuild.IQuickBuildTarget;

/**
 * The proxy app's end of the deploy channel to CoGo.
 *
 * Binds to CoGo's Quick Build service with an explicit action and package plus BIND_AUTO_CREATE, registers the {@link IQuickBuildTarget} callback, and carries reload and crash reports back. Every remote call is guarded, so losing CoGo degrades the proxy app rather than crashing it.
 *
 * BIND_AUTO_CREATE keeps the binding alive across a CoGo service restart: the framework reconnects and {@link #onServiceConnected} re-runs connect with the current running generation, which is how a relaunched proxy app catches up to the newest payload. Manual rebinds with backoff cover what the framework does not retry - a failed bind call, a dead binding, a null binding.
 */
final class QuickBuildClient implements ServiceConnection {

	/** Intent action of CoGo's deploy service. */
	static final String SERVICE_ACTION = "com.itsaky.androidide.QUICK_BUILD_ACTION";

	/** CoGo's package name (same constant the LogSender uses). */
	static final String IDE_PACKAGE = "com.itsaky.androidide";

	/** First rebind delay, doubled per failed attempt. */
	private static final int REBIND_MIN_DELAY_MS = 1000;

	/** Ceiling for the doubling, so a CoGo that never comes back costs one attempt per 30s. */
	private static final int REBIND_MAX_DELAY_MS = 30000;

	private final QuickBuildRuntime runtime;

	/** Rebinds are posted here, so bindService is always called from the main thread. */
	private final Handler mainHandler = new Handler(Looper.getMainLooper());

	/** Application context, volatile because binder threads read it. */
	private volatile Context appContext;

	/** The live host proxy, or null while disconnected; volatile for the same reason. */
	private volatile IQuickBuildHost host;

	/** True once {@link #bind} has run, which is what makes that call idempotent. */
	private boolean bindRequested;

	/** True while a rebind is queued, so failures cannot pile up attempts. */
	private boolean rebindScheduled;

	/** Delay for the next rebind; reset to the minimum on every successful connect. */
	private int rebindDelayMs = REBIND_MIN_DELAY_MS;

	/** The callback CoGo drives; every method hands straight to the runtime's guarded handlers. */
	private final IQuickBuildTarget.Stub target = new IQuickBuildTarget.Stub() {

		/**
		 * @param statusJson
		 *            the build status document, forwarded verbatim for parsing
		 */
		@Override
		public void onBuildStatus(String statusJson) {
			// Oneway call, arrives on a binder thread. handleBuildStatus guards all
			// throwables itself; nothing may escape into the binder.
			runtime.handleBuildStatus(statusJson);
		}

		/**
		 * @param generation
		 *            the payload's generation, which must be strictly newer to be applied
		 * @param dexPayload
		 *            the dex bytes, or null when this deploy changed no code
		 * @param resourcesPayload
		 *            the relinked resource apk, or null when no resources changed
		 * @param assetsPayload
		 *            the changed-assets zip, or null when no assets changed
		 * @param metadataJson
		 *            the deploy metadata document
		 */
		@Override
		public void onPayload(long generation, ParcelFileDescriptor dexPayload,
				ParcelFileDescriptor resourcesPayload, ParcelFileDescriptor assetsPayload,
				String metadataJson) {
			// Oneway call, arrives on a binder thread. handlePayload guards all
			// throwables itself; nothing may escape into the binder.
			runtime.handlePayload(generation, dexPayload, resourcesPayload, assetsPayload,
					metadataJson);
		}
	};

	/**
	 * @param runtime
	 *            the runtime this client reports to and reads the running generation from
	 */
	QuickBuildClient(QuickBuildRuntime runtime) {
		this.runtime = runtime;
	}

	/**
	 * Drops the dead binding and queues a fresh one, since the framework will not revive it.
	 *
	 * @param name
	 *            CoGo's service component; unused, there is only one binding
	 */
	@Override
	public void onBindingDied(ComponentName name) {
		RuntimeLog.w("binding to CoGo died; rebinding");
		host = null;
		unbindQuietly();
		scheduleRebind();
	}

	/**
	 * Treats a null binding as a not-ready CoGo and retries with backoff.
	 *
	 * @param name
	 *            CoGo's service component; unused, there is only one binding
	 */
	@Override
	public void onNullBinding(ComponentName name) {
		RuntimeLog.w("CoGo returned a null binding; retrying later");
		host = null;
		unbindQuietly();
		scheduleRebind();
	}

	/**
	 * Registers this app with CoGo, naming the generation it currently runs so CoGo can send the catch-up payload.
	 *
	 * @param name
	 *            CoGo's service component; unused, there is only one binding
	 * @param service
	 *            the host binder, which may still be null in practice, hence the check
	 */
	@Override
	public void onServiceConnected(ComponentName name, IBinder service) {
		IQuickBuildHost connected = IQuickBuildHost.Stub.asInterface(service);
		if (connected == null) {
			RuntimeLog.w("null host proxy from onServiceConnected");
			scheduleRebind();
			return;
		}
		host = connected;
		synchronized (this) {
			rebindDelayMs = REBIND_MIN_DELAY_MS;
		}
		try {
			Context context = appContext;
			String packageName = context == null ? "" : context.getPackageName();
			connected.connect(target, packageName, runtime.runningGeneration());
			RuntimeLog.i("connected to CoGo (running gen " + runtime.runningGeneration() + ")");
		} catch (RemoteException error) {
			RuntimeLog.e("connect() to CoGo failed", error);
			host = null;
			scheduleRebind();
		}
	}

	/**
	 * Forgets the host and waits, because the framework reconnects this binding itself.
	 *
	 * @param name
	 *            CoGo's service component; unused, there is only one binding
	 */
	@Override
	public void onServiceDisconnected(ComponentName name) {
		// The binding stays valid; the framework restarts the service (BIND_AUTO_CREATE)
		// and calls onServiceConnected again. Do NOT rebind manually here - a second
		// bindService with the same connection would stack bindings.
		RuntimeLog.w("CoGo deploy service disconnected; awaiting reconnect");
		host = null;
	}

	/**
	 * Starts the binding to CoGo. Idempotent, so it is safe to call once per activity.
	 *
	 * @param context
	 *            any context; only its application context is retained, so no activity leaks
	 */
	synchronized void bind(Context context) {
		if (bindRequested) {
			return;
		}
		bindRequested = true;
		appContext = context.getApplicationContext();
		if (!bindNow()) {
			scheduleRebind();
		}
	}

	/**
	 * Tells CoGo a generation crashed and was rolled back. Best-effort: a lost host is logged, never fatal.
	 *
	 * @param generation
	 *            the generation that crashed, which CoGo marks bad so it is not re-sent
	 * @param stackSummary
	 *            one-line summary of the crash, for CoGo to show the developer
	 */
	void reportCrash(long generation, String stackSummary) {
		IQuickBuildHost current = host;
		if (current == null) {
			RuntimeLog.w("cannot report crash for gen " + generation + ": not connected");
			return;
		}
		try {
			current.reportCrash(generation, stackSummary);
		} catch (RemoteException error) {
			RuntimeLog.e("reportCrash failed", error);
		}
	}

	/**
	 * Tells CoGo a generation reloaded and how long it took. Best-effort: a lost host is logged, never fatal.
	 *
	 * @param generation
	 *            the generation now running, which becomes CoGo's new baseline
	 * @param reloadMillis
	 *            wall-clock time from payload arrival to the screen being back, the number the IDE reports to the developer
	 */
	void reportReloaded(long generation, long reloadMillis) {
		IQuickBuildHost current = host;
		if (current == null) {
			RuntimeLog.w("cannot report reloaded gen " + generation + ": not connected");
			return;
		}
		try {
			current.reportReloaded(generation, reloadMillis);
		} catch (RemoteException error) {
			RuntimeLog.e("reportReloaded failed", error);
		}
	}

	/**
	 * Issues one bindService against CoGo's explicit service intent.
	 *
	 * @return true when the framework accepted the bind; false when there is no context yet, CoGo is not installed, or bindService threw. A true here only means the request was accepted - {@link #onServiceConnected} is what confirms the channel.
	 */
	private boolean bindNow() {
		Context context = appContext;
		if (context == null) {
			return false;
		}
		Intent intent = new Intent(SERVICE_ACTION);
		intent.setPackage(IDE_PACKAGE);
		try {
			boolean binding = context.bindService(intent, this,
					Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT);
			if (!binding) {
				RuntimeLog.w("bindService returned false; is CoGo installed?");
			}
			return binding;
		} catch (Throwable error) {
			RuntimeLog.e("bindService failed", error);
			return false;
		}
	}

	/** Queues one rebind attempt, doubling the delay up to {@link #REBIND_MAX_DELAY_MS}. */
	private synchronized void scheduleRebind() {
		if (rebindScheduled) {
			return;
		}
		rebindScheduled = true;
		int delay = rebindDelayMs;
		rebindDelayMs = Math.min(rebindDelayMs * 2, REBIND_MAX_DELAY_MS);
		mainHandler.postDelayed(new Runnable() {

			@Override
			public void run() {
				synchronized (QuickBuildClient.this) {
					rebindScheduled = false;
				}
				if (host != null) {
					return;
				}
				if (!bindNow()) {
					scheduleRebind();
				}
			}
		}, delay);
	}

	/** Unbinds, ignoring the not-registered case that a dead binding can produce. */
	private void unbindQuietly() {
		Context context = appContext;
		if (context == null) {
			return;
		}
		try {
			context.unbindService(this);
		} catch (Throwable error) {
			RuntimeLog.d("unbindService: " + error);
		}
	}
}
