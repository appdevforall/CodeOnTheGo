package com.itsaky.androidide.quickbuild

import org.appdevforall.cotg.quickbuild.domain.BuildOutcome
import org.appdevforall.cotg.quickbuild.domain.BuildRoute
import org.appdevforall.cotg.quickbuild.domain.ChangedFiles
import org.appdevforall.cotg.quickbuild.domain.E2eTimeline
import org.appdevforall.cotg.quickbuild.domain.InvalidationReason
import org.appdevforall.cotg.quickbuild.domain.QuickBuildMetricsSink
import org.appdevforall.cotg.quickbuild.domain.SameAppIdRefusalReason
import org.slf4j.LoggerFactory

/**
 * Fans every [QuickBuildMetricsSink] callback out to several delegates (ADFA-4128: the
 * shipping analytics sink plus the benchmark JSON-lines sink). Each delegate call is
 * guarded, so one misbehaving sink can never stop the others or break a build - a thrown
 * delegate degrades to a logged warning.
 *
 * Every method (including the interface's defaulted ones) is overridden so a defaulted
 * event still reaches the delegates that implement it; leaving one to the interface
 * default would silently drop it for all delegates.
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

	override fun onRebaseline(
		isSuccess: Boolean,
		durationMillis: Long,
	) = fanOut { it.onRebaseline(isSuccess, durationMillis) }

	override fun onSameAppIdEntered(updateInstall: Boolean) = fanOut { it.onSameAppIdEntered(updateInstall) }

	override fun onSameAppIdClobberConfirmed() = fanOut { it.onSameAppIdClobberConfirmed() }

	override fun onSameAppIdRefused(reason: SameAppIdRefusalReason) = fanOut { it.onSameAppIdRefused(reason) }

	override fun onSameAppIdRestored(downgradeUsed: Boolean) = fanOut { it.onSameAppIdRestored(downgradeUsed) }

	companion object {
		private val log = LoggerFactory.getLogger(CompositeQuickBuildMetricsSink::class.java)
	}
}
