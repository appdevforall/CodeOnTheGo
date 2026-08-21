package com.itsaky.androidide.quickbuild

import org.appdevforall.cotg.quickbuild.domain.ChangedFiles
import org.appdevforall.cotg.quickbuild.domain.classify.BuildRoute
import org.appdevforall.cotg.quickbuild.domain.classify.InvalidationReason
import org.appdevforall.cotg.quickbuild.domain.reload.BuildOutcome
import org.appdevforall.cotg.quickbuild.domain.telemetry.E2eTimeline
import org.appdevforall.cotg.quickbuild.domain.telemetry.QuickBuildMetricsSink

/**
 * Puts each landed build's stage timings in the Build Output pane (ADFA-4128).
 *
 * The timings ride the metrics port rather than the session status: [E2eTimeline] is the only
 * type that carries the per-stage split, and it reaches the app layer here. Everything else on
 * this port is a statistic with no place in a log the user reads, so it is dropped.
 *
 * @property narrator where the rendered line goes.
 */
class QuickBuildOutputMetricsSink(
	private val narrator: QuickBuildOutputNarrator,
) : QuickBuildMetricsSink {
	override fun onReloadTimeline(timeline: E2eTimeline) = narrator.narrate(timeline)

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
		relaunchOk: Boolean,
		toRunningMillis: Long?,
	) = Unit
}
