package org.appdevforall.cotg.quickbuild.domain.telemetry

import org.appdevforall.cotg.quickbuild.domain.ChangedFiles
import org.appdevforall.cotg.quickbuild.domain.classify.BuildRoute
import org.appdevforall.cotg.quickbuild.domain.classify.InvalidationReason
import org.appdevforall.cotg.quickbuild.domain.reload.BuildOutcome

/**
 * Port for per-build run statistics: change-set size, route, run time, invalidations, and
 * proxy-app-rebuild cost. The app layer wires an analytics-backed implementation; the domain
 * knows only this interface.
 *
 * Implementations must be cheap and must not throw - metrics can never affect a build.
 * Callers guard every call, so a misbehaving sink degrades to a logged warning.
 */
interface QuickBuildMetricsSink {
	/**
	 * Records the start of a new live session.
	 *
	 * Build ids restart at 1 per session, so a sink that exports them must mint a fresh
	 * session id here to keep (session, build) unique.
	 */
	fun onSessionStarted()

	/**
	 * Records a quick build leaving the queue.
	 *
	 * @param buildId orchestrator-unique id, restarting at 1 each session; pair it with the
	 *   session id minted in [onSessionStarted] to key a row.
	 * @param route the path chosen for this change-set, never [BuildRoute.FullGradleBuild] -
	 *   that one leaves the live reload path before a build starts.
	 * @param changes the coalesced set the route was computed from.
	 */
	fun onBuildStarted(
		buildId: Long,
		route: BuildRoute,
		changes: ChangedFiles,
	)

	/**
	 * Records the build's outcome, successful or not. Pairs 1:1 with [onBuildStarted].
	 *
	 * @param buildId the id the matching [onBuildStarted] carried.
	 * @param outcome how the build ended; only [BuildOutcome.Success] moved the proxy app to a
	 *   new generation.
	 */
	fun onBuildFinished(
		buildId: Long,
		outcome: BuildOutcome,
	)

	/**
	 * Records a change set that forced the session off the live reload path.
	 *
	 * @param reason what the live reload path could not absorb; every value costs a full Gradle
	 *   build.
	 */
	fun onInvalidation(reason: InvalidationReason)

	/**
	 * Records one completed save->live loop, both the end-to-end time and the per-stage split.
	 * Fired once per successful deploy, keyed by generation id. Defaulted so existing sinks
	 * stay source-compatible.
	 *
	 * @param timeline the four monotonic stamps bounding the loop plus any reported step
	 *   timings; read its deltas, never its absolute stamps.
	 */
	fun onReloadTimeline(timeline: E2eTimeline) {}

	/**
	 * Records a finished full proxy app rebuild - the cost of every fallback route.
	 *
	 * @param isSuccess whether the rebuild produced an installable proxy app; a declined or
	 *   unconfirmed install still counts as a failure here.
	 * @param durationMillis wall-clock cost of the Gradle build, in milliseconds.
	 */
	fun onProxyAppRebuild(
		isSuccess: Boolean,
		durationMillis: Long,
	)

	/** Sink that records nothing. */
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
