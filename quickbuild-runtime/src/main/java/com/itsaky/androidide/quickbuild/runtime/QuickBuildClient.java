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
 * The test app's end of the deploy channel: binds to CoGo's Quick Build service (the LogSender bind pattern - explicit action + package, BIND_AUTO_CREATE), registers the {@link IQuickBuildTarget} callback, and carries the reload/crash reports back.
 *
 * Connection lifecycle: BIND_AUTO_CREATE keeps the binding alive across a CoGo service restart (the framework reconnects and {@link #onServiceConnected} re-runs connect with the CURRENT running generation, which is how a killed-and-relaunched test app catches up to the newest payload). Manual rebinds with backoff cover the cases the framework does not retry: a failed bind call, a dead binding, a null binding.
 *
 * Every remote call is guarded - losing CoGo must degrade the test app, never crash it.
 */
final class QuickBuildClient implements ServiceConnection {

	/** Intent action of CoGo's deploy service. */
	static final String SERVICE_ACTION = "com.itsaky.androidide.QUICK_BUILD_ACTION";

	/** CoGo's package name (same constant the LogSender uses). */
	static final String IDE_PACKAGE = "com.itsaky.androidide";

	private static final int REBIND_MIN_DELAY_MS = 1000;
	private static final int REBIND_MAX_DELAY_MS = 30000;

	private final QuickBuildRuntime runtime;
	private final Handler mainHandler = new Handler(Looper.getMainLooper());

	private volatile Context appContext;
	private volatile IQuickBuildHost host;
	private boolean bindRequested;
	private boolean rebindScheduled;
	private int rebindDelayMs = REBIND_MIN_DELAY_MS;

	private final IQuickBuildTarget.Stub target = new IQuickBuildTarget.Stub() {

		@Override
		public void onBuildStatus(String statusJson) {
			// Oneway call, arrives on a binder thread. handleBuildStatus guards all
			// throwables itself; nothing may escape into the binder.
			runtime.handleBuildStatus(statusJson);
		}

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

	QuickBuildClient(QuickBuildRuntime runtime) {
		this.runtime = runtime;
	}

	@Override
	public void onBindingDied(ComponentName name) {
		RuntimeLog.w("binding to CoGo died; rebinding");
		host = null;
		unbindQuietly();
		scheduleRebind();
	}

	@Override
	public void onNullBinding(ComponentName name) {
		RuntimeLog.w("CoGo returned a null binding; retrying later");
		host = null;
		unbindQuietly();
		scheduleRebind();
	}

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

	@Override
	public void onServiceDisconnected(ComponentName name) {
		// The binding stays valid; the framework restarts the service (BIND_AUTO_CREATE)
		// and calls onServiceConnected again. Do NOT rebind manually here - a second
		// bindService with the same connection would stack bindings.
		RuntimeLog.w("CoGo deploy service disconnected; awaiting reconnect");
		host = null;
	}

	/** Starts (or re-verifies) the binding. Idempotent; safe to call per activity. */
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

	/** Best-effort report; a lost host is logged, never fatal. */
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

	/** Best-effort report; a lost host is logged, never fatal. */
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
