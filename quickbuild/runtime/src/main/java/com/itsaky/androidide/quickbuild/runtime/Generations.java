package com.itsaky.androidide.quickbuild.runtime;

/**
 * Holds the generation acceptance rule, so it is stated once and JVM-testable.
 */
final class Generations {

	/**
	 * Decides whether an incoming payload may replace the running one.
	 *
	 * Only a strictly newer generation is accepted (IQuickBuildTarget contract); an equal or older one is a replay from a deploy racing a reconnect and must be dropped.
	 *
	 * @param runningGeneration
	 *            generation of the payload already live in this process; 0 when nothing has been applied yet
	 * @param incomingGeneration
	 *            generation stamped on the arriving payload by the deploying host
	 * @return true when the incoming payload is strictly newer and the caller should apply it
	 */
	static boolean accepts(long runningGeneration, long incomingGeneration) {
		return incomingGeneration > runningGeneration;
	}

	/**
	 * Decides whether a failed reload's rollback still applies.
	 *
	 * A reload's rollback snapshot is taken before its apply, but the failure can surface much later - the recreate runs on a posted main-thread runnable, and a newer payload can land on a binder thread in the meantime. Restoring then would drop the store to a snapshot two generations old, undoing a deploy that succeeded. The rollback is only ever the right answer while the store still holds the generation that failed.
	 *
	 * @param runningGeneration
	 *            generation the store holds right now
	 * @param failedGeneration
	 *            generation whose reload failed and wants to roll back
	 * @return true when the failure still owns the store and the caller should restore
	 */
	static boolean rollbackApplies(long runningGeneration, long failedGeneration) {
		return runningGeneration == failedGeneration;
	}

	private Generations() {}
}
