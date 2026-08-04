package com.itsaky.androidide.quickbuild.runtime;

/**
 * Holds the generation acceptance rule, so it is stated once and JVM-testable.
 */
final class Generations {

	/**
	 * Decides whether an incoming payload may replace the running one.
	 *
	 * Only a strictly newer generation is accepted (IQuickBuildTarget contract); an equal or
	 * older one is a replay from a deploy racing a reconnect and must be dropped.
	 *
	 * @param runningGeneration generation of the payload already live in this process; 0 when
	 *     nothing has been applied yet
	 * @param incomingGeneration generation stamped on the arriving payload by the deploying host
	 * @return true when the incoming payload is strictly newer and the caller should apply it
	 */
	static boolean accepts(long runningGeneration, long incomingGeneration) {
		return incomingGeneration > runningGeneration;
	}

	private Generations() {}
}
