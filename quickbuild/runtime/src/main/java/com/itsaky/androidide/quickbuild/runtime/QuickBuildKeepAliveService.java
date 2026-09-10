package com.itsaky.androidide.quickbuild.runtime;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;

/**
 * A featureless bound service CoGo binds into, keeping this process out of Android's cached-app freezer while a Quick Build session is open: the proxy app has no foreground activity during the edit loop, and a frozen process runs no binder threads, so every save would fail the deploy timeout.
 *
 * It has to run this way round because a binding raises the priority of the process hosting the SERVICE, not the client's, so {@link QuickBuildClient}'s outward bind to CoGo confers nothing here. Named in the Gradle plugin's {@code ComponentProxiabilityResolver.UNPROXIABLE_BY_NAME} so the proxy-app manifest transform keeps the exact name CoGo binds by.
 *
 * Final on purpose: it is not a hot-swap target, and the final flag is a second, independent reason for the manifest transform to skip it.
 */
public final class QuickBuildKeepAliveService extends Service {

	/** Handed to every binder; carries no operations because the binding is the whole point. */
	private final IBinder binder = new Binder();

	/**
	 * Accepts the bind that keeps this process unfrozen.
	 *
	 * @param intent
	 *            CoGo's explicit bind intent; nothing is read from it
	 * @return a featureless binder, never null - a null binding would leave the caller retrying and this process cached
	 */
	@Override
	public IBinder onBind(Intent intent) {
		RuntimeLog.i("keep-alive bound; this process is no longer freezer-eligible");
		return binder;
	}

	/**
	 * Notes that the process is cacheable again, which is the correct state once no session can deploy to it.
	 *
	 * @param intent
	 *            the intent originally used to bind; nothing is read from it
	 * @return false, so a later rebind gets {@link #onBind} again rather than onRebind
	 */
	@Override
	public boolean onUnbind(Intent intent) {
		RuntimeLog.i("keep-alive unbound; this process is freezer-eligible again");
		return false;
	}
}
