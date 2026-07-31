package com.itsaky.androidide.quickbuild.runtime;

import android.app.Service;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Live-service census. Generated {@code Proxy<N>Service} subclasses call {@link #onServiceCreated}/{@link #onServiceDestroyed} from their onCreate/onDestroy overrides (super always called first), giving the runtime an honest count of running services - the input for restart UX messaging and the tracked policy tightening ("restart on any code deploy while a service is live").
 *
 * Public (unlike the rest of the runtime) because the callers are generated classes in the user's payload package. Identity-based so the census never depends on user equals/hashCode overrides. Best-effort: a census failure must never crash a user's service lifecycle.
 */
public final class ServiceTracker {

	private static final Set<Object> LIVE = Collections
			.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>()));

	/** True while at least one tracked service is created and not yet destroyed. */
	public static boolean hasLiveServices() {
		return !LIVE.isEmpty();
	}

	/** Number of live (created, not yet destroyed) tracked services. */
	public static int liveCount() {
		return LIVE.size();
	}

	/** Called by generated service proxies from onCreate. Never throws. */
	public static void onServiceCreated(Service service) {
		trackCreated(service);
		try {
			RuntimeLog.i("service live: " + service.getClass().getName()
					+ " (count " + LIVE.size() + ")");
		} catch (Throwable ignored) {
			// Census over crash: logging must not take down a user service.
		}
	}

	/** Called by generated service proxies from onDestroy. Never throws. */
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

	// Object-typed seams carry the census logic so it is JVM-unit-testable
	// (android.app.Service is not on the unit-test classpath).

	static void trackCreated(Object service) {
		if (service != null) {
			LIVE.add(service);
		}
	}

	static void trackDestroyed(Object service) {
		if (service != null) {
			LIVE.remove(service);
		}
	}

	private ServiceTracker() {}
}
