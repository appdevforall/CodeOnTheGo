package org.appdevforall.cotg.quickbuild.domain

/**
 * Port for per-build run statistics (ADFA-4128 tracking; David's ask on the design doc):
 * change-set size, route, run time, invalidations, proxy-app-rebuild cost - the signals needed
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

	/**
	 * One generation reached the user: the full save->live loop completed. Carries the
	 * end-to-end time AND the per-stage split ([E2eTimeline]) so the tuning data has both
	 * the user-perceived number and where the time went. Keyed by generation id (the same
	 * id the completed event reports), fired once per successful deploy. Default no-op keeps
	 * existing sinks source-compatible. `[ADFA-4128 e2e-timing]`
	 */
	fun onReloadTimeline(timeline: E2eTimeline) {}

	/** A proxy app rebuild (full proxy app rebuild) finished; the cost of every fallback route. */
	fun onProxyAppRebuild(
		isSuccess: Boolean,
		durationMillis: Long,
	)

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

		override fun onProxyAppRebuild(
			isSuccess: Boolean,
			durationMillis: Long,
		) = Unit
	}
}
