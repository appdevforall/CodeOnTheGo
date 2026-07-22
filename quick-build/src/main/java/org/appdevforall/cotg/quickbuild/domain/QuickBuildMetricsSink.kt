package org.appdevforall.cotg.quickbuild.domain

/**
 * Port for per-build run statistics (ADFA-4128 tracking; David's ask on the design doc):
 * change-set size, route, run time, invalidations, rebaseline cost - the signals needed
 * to tune the fast path on real data. The app layer wires an analytics-backed sink; the
 * domain only knows this interface so dependencies keep flowing down.
 *
 * Contract: implementations must be cheap and must not throw - metrics can never affect
 * a build. Callers additionally guard every call, so a misbehaving sink degrades to a
 * logged warning.
 */
interface QuickBuildMetricsSink {
	/**
	 * A new live session started. Build ids restart at 1 per session, so a sink that
	 * exports them must mint a fresh session id here to keep (session, build) unique -
	 * the same shape as Gradle's BuildId(buildSessionId, counter).
	 */
	fun onSessionStarted()

	/** A quick build left the queue. [changes] is the coalesced set the route was computed from. */
	fun onBuildStarted(
		buildId: Long,
		route: BuildRoute,
		changes: ChangedFiles,
	)

	/** The build finished, successfully or not. Pairs 1:1 with [onBuildStarted]. */
	fun onBuildFinished(
		buildId: Long,
		outcome: BuildOutcome,
	)

	/** The changed-set forced the session off the fast path (David's "significant events"). */
	fun onInvalidation(reason: InvalidationReason)

	/** A rebaseline (full setup rebuild) finished; the cost of every fallback route. */
	fun onRebaseline(
		isSuccess: Boolean,
		durationMillis: Long,
	)

	/**
	 * Same-app-id mode (contract section 6): the clobber warning was accepted and
	 * provisioning will start. [updateInstall] = the data-preserving update path vs a
	 * plain fresh install. Default no-op so existing sinks stay source-compatible.
	 */
	fun onSameAppIdEntered(updateInstall: Boolean) {}

	/** The clobber dialog's accept itself; kept distinct so decline rate is measurable. */
	fun onSameAppIdClobberConfirmed() {}

	/** Mode entry was refused; [reason] is signature_mismatch, user_declined, or version_code_overflow. */
	fun onSameAppIdRefused(reason: SameAppIdRefusalReason) {}

	/** A Standard Run ended the mode episode; [downgradeUsed] = restore requested a downgrade. */
	fun onSameAppIdRestored(downgradeUsed: Boolean) {}

	object Noop : QuickBuildMetricsSink {
		override fun onSessionStarted() = Unit

		override fun onBuildStarted(
			buildId: Long,
			route: BuildRoute,
			changes: ChangedFiles,
		) = Unit

		override fun onBuildFinished(
			buildId: Long,
			outcome: BuildOutcome,
		) = Unit

		override fun onInvalidation(reason: InvalidationReason) = Unit

		override fun onRebaseline(
			isSuccess: Boolean,
			durationMillis: Long,
		) = Unit
	}
}
