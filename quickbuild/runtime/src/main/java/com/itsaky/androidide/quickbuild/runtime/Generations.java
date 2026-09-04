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
	 * What a failed reload owes, decided from where the store stands relative to the failure.
	 *
	 * The three cases matter because {@link #rollbackApplies} alone conflates two of them: a failure superseded by a newer deploy must stay silent, but a failure the store never adopted - an oversize payload, a full disk, a restart deploy missing its dex - still has to reach the host and the banner, or its only trace is the host's deploy timeout.
	 *
	 * @param runningGeneration
	 *            generation the store holds right now
	 * @param failedGeneration
	 *            generation whose reload failed
	 * @return the action the failure path must take
	 */
	static FailureAction onReloadFailure(long runningGeneration, long failedGeneration) {
		if (rollbackApplies(runningGeneration, failedGeneration)) {
			return FailureAction.ROLLBACK_AND_REPORT;
		}
		return runningGeneration > failedGeneration
				? FailureAction.LEAVE_ALONE
				: FailureAction.REPORT_ONLY;
	}

	/**
	 * Whether a failed reload has left the process serving two generations at once.
	 *
	 * The rollback restores the dex, but a resource swap that already committed cannot be undone: the store keeps single provider slots and closes the replaced one, and the API 28/29 path cannot unmount an asset path at all. A deploy posts its table swap and then does the asset merge on the binder thread, so when the merge throws the swap has usually landed. The app then runs the previous generation's classes over the failed generation's resources until a resources-carrying deploy or a process restart, and a banner claiming the last working version would be false.
	 *
	 * @param swappedGeneration
	 *            the newest generation whose resource swap committed, or -1 before any
	 * @param failedGeneration
	 *            the generation whose reload failed
	 * @return true when the failed generation's resources are what the screen resolves against
	 */
	static boolean leavesMixedState(long swappedGeneration, long failedGeneration) {
		return swappedGeneration == failedGeneration;
	}

	/**
	 * The pending-reload generation the runtime should hold after a payload applies.
	 *
	 * A foreground apply leaves the generation pending until its first resumed frame acks it. A backgrounded apply acks at apply time, and the pending slot must still be assigned - not skipped: leaving an older generation's pending value behind is what let the crash guard blame it for a later generation's crash, and let the crashing generation escape quarantine.
	 *
	 * @param resumedActivity
	 *            whether an activity is resumed, i.e. whether there is a frame to prove the reload on
	 * @param generation
	 *            the generation just applied
	 * @return the value the pending slot must take: the generation while its ack waits for a frame, or -1 when the apply was already acked
	 */
	static long pendingAfterApply(boolean resumedActivity, long generation) {
		return resumedActivity ? generation : -1;
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

	/** What {@link #onReloadFailure} tells the failure path to do. */
	enum FailureAction {
		/** The store still holds the failed generation: roll back, quarantine, report, banner. */
		ROLLBACK_AND_REPORT,
		/** The store never adopted the failed generation: nothing to roll back or quarantine, but report and banner still fire. */
		REPORT_ONLY,
		/** A newer generation owns the store, the pending ack and the screen: touch nothing, say nothing. */
		LEAVE_ALONE
	}
}
