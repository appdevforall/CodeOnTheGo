package com.itsaky.androidide.quickbuild.runtime;

/**
 * The generation acceptance rule, isolated so it is JVM-testable and stated once.
 *
 * A payload is accepted only when it is STRICTLY newer than the running generation (IQuickBuildTarget contract). Strictly-newer is what makes "an old payload can never replace a newer one" true even when deploys race a reconnect: an equal or older generation is always a replay and must be dropped.
 */
final class Generations {

	static boolean accepts(long runningGeneration, long incomingGeneration) {
		return incomingGeneration > runningGeneration;
	}

	private Generations() {}
}
