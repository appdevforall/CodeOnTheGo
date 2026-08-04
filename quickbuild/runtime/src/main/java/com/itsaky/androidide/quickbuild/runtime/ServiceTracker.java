package com.itsaky.androidide.quickbuild.runtime;

import android.app.Service;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Counts the proxy app's live services, so the runtime knows whether a deploy needs a restart.
 *
 * Generated {@code Proxy<N>Service} subclasses call {@link #onServiceCreated} and
 * {@link #onServiceDestroyed} from their onCreate and onDestroy overrides. Public, unlike the rest
 * of the runtime, because those callers live in the user's payload package. The census is
 * identity-based so it never depends on a user's equals or hashCode, and a census failure must
 * never crash a user's service lifecycle.
 */
public final class ServiceTracker {

	/** The live census, identity-keyed and synchronized because services start on any thread. */
	private static final Set<Object> LIVE = Collections
			.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>()));

	/**
	 * True while at least one tracked service is created and not yet destroyed.
	 *
	 * @return whether a deploy touching service code has to restart the process instead of
	 *     hot-swapping
	 */
	public static boolean hasLiveServices() {
		return !LIVE.isEmpty();
	}

	/**
	 * Number of live (created, not yet destroyed) tracked services.
	 *
	 * @return the census size, for logging; only generated proxies are counted, so a service
	 *     without a proxy is invisible here
	 */
	public static int liveCount() {
		return LIVE.size();
	}

	/**
	 * Records a service as live; called by generated service proxies from onCreate. Never throws.
	 *
	 * @param service the service instance to count, tracked by identity and never dereferenced
	 *     beyond its class name; null is ignored
	 */
	public static void onServiceCreated(Service service) {
		trackCreated(service);
		try {
			RuntimeLog.i("service live: " + service.getClass().getName()
					+ " (count " + LIVE.size() + ")");
		} catch (Throwable ignored) {
			// Census over crash: logging must not take down a user service.
		}
	}

	/**
	 * Drops a service from the census; called by generated service proxies from onDestroy. Never
	 * throws.
	 *
	 * @param service the same instance passed to {@link #onServiceCreated}; an untracked or null
	 *     one is ignored
	 */
	public static void onServiceDestroyed(Service service) {
		trackDestroyed(service);
		try {
			RuntimeLog.i("service destroyed: " + service.getClass().getName()
					+ " (count " + LIVE.size() + ")");
		} catch (Throwable ignored) {
			// Census over crash: logging must not take down a user service.
		}
	}

	/** Test-only: clears the census (static state must not leak between tests). */
	static void reset() {
		LIVE.clear();
	}

	// The census logic takes Object so it is JVM-unit-testable: android.app.Service
	// is not on the unit-test classpath.

	/**
	 * Adds a service to the census.
	 *
	 * @param service the instance to track by identity; null is ignored
	 */
	static void trackCreated(Object service) {
		if (service != null) {
			LIVE.add(service);
		}
	}

	/**
	 * Removes a service from the census.
	 *
	 * @param service the instance to stop tracking; null or untracked is ignored
	 */
	static void trackDestroyed(Object service) {
		if (service != null) {
			LIVE.remove(service);
		}
	}

	private ServiceTracker() {}
}
