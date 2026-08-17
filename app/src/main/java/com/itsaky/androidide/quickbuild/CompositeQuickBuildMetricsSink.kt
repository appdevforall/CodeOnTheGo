package com.itsaky.androidide.quickbuild

import org.appdevforall.cotg.quickbuild.domain.ChangedFiles
import org.appdevforall.cotg.quickbuild.domain.classify.BuildRoute
import org.appdevforall.cotg.quickbuild.domain.classify.InvalidationReason
import org.appdevforall.cotg.quickbuild.domain.reload.BuildOutcome
import org.appdevforall.cotg.quickbuild.domain.telemetry.E2eTimeline
import org.appdevforall.cotg.quickbuild.domain.telemetry.QuickBuildMetricsSink
import org.slf4j.LoggerFactory

/**
 * Fans every [QuickBuildMetricsSink] callback out to several delegates. Each delegate call is
 * guarded, so one misbehaving sink can never stop the others or break a build.
 *
 * Every method (including the interface's defaulted ones) is overridden so a defaulted event still
 * reaches the delegates that implement it; leaving one to the interface default would silently drop
 * it for all delegates.
 */
class CompositeQuickBuildMetricsSink(
	private vararg val delegates: QuickBuildMetricsSink,
) : QuickBuildMetricsSink {
	private fun fanOut(action: (QuickBuildMetricsSink) -> Unit) {
		for (delegate in delegates) {
			runCatching { action(delegate) }
				.onFailure { log.warn("Quick Build metrics delegate threw", it) }
		}
	}

	override fun onSessionStarted() = fanOut { it.onSessionStarted() }

	override fun onBuildStarted(
		buildId: Long,
		route: BuildRoute,
		changes: ChangedFiles,
	) = fanOut { it.onBuildStarted(buildId, route, changes) }

	override fun onBuildFinished(
		buildId: Long,
		outcome: BuildOutcome,
	) = fanOut { it.onBuildFinished(buildId, outcome) }

	override fun onReloadTimeline(timeline: E2eTimeline) = fanOut { it.onReloadTimeline(timeline) }

	override fun onInvalidation(reason: InvalidationReason) = fanOut { it.onInvalidation(reason) }

	override fun onProxyAppRebuild(
		isSuccess: Boolean,
		durationMillis: Long,
	) = fanOut { it.onProxyAppRebuild(isSuccess, durationMillis) }

	companion object {
		private val log = LoggerFactory.getLogger("QB-MetricsSink")
	}
}
