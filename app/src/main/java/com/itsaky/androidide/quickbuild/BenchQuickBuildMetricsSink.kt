package com.itsaky.androidide.quickbuild

import org.appdevforall.cotg.quickbuild.domain.BuildOutcome
import org.appdevforall.cotg.quickbuild.domain.BuildRoute
import org.appdevforall.cotg.quickbuild.domain.ChangedFiles
import org.appdevforall.cotg.quickbuild.domain.E2eTimeline
import org.appdevforall.cotg.quickbuild.domain.InvalidationReason
import org.appdevforall.cotg.quickbuild.domain.QuickBuildMetricsSink

/**
 * [QuickBuildMetricsSink] that mirrors every callback into [BenchEventsFile] for the
 * ADFA-4128 harness. `reload_timeline` is the load-bearing event: it carries the whole
 * save->live loop the benchmark reads. Enabled only under the bench flag, alongside the
 * analytics sink (see [CompositeQuickBuildMetricsSink]).
 */
class BenchQuickBuildMetricsSink(
	private val events: BenchEventsFile,
) : QuickBuildMetricsSink {
	override fun onSessionStarted() {
		events.append("session_started")
	}

	override fun onBuildStarted(
		buildId: Long,
		route: BuildRoute,
		changes: ChangedFiles,
	) {
		events.append("build_started") {
			put("buildId", buildId)
			put("route", route::class.simpleName ?: "Unknown")
		}
	}

	override fun onBuildFinished(
		buildId: Long,
		outcome: BuildOutcome,
	) {
		events.append("build_finished") {
			put("buildId", buildId)
			put("outcome", outcome::class.simpleName ?: "Unknown")
		}
	}

	override fun onReloadTimeline(timeline: E2eTimeline) {
		events.append("reload_timeline") {
			put("generation", timeline.generation)
			put("trigger", timeline.trigger)
			put("compileDone", timeline.compileDone)
			put("deploySent", timeline.deploySent)
			put("reloadLive", timeline.reloadLive)
			put("totalMs", timeline.totalMillis)
			// Per-tool step durations (additive fields; absent when the step didn't run).
			// This JSON event - not any log line - is the harness's sub-step contract.
			timeline.steps?.let { steps ->
				steps.kotlinMillis?.let { put("kotlinMs", it) }
				steps.javaMillis?.let { put("javacMs", it) }
				steps.stripMillis?.let { put("stripMs", it) }
				steps.d8Millis?.let { put("d8Ms", it) }
				steps.aapt2CompileMillis?.let { put("aapt2CompileMs", it) }
				steps.aapt2LinkMillis?.let { put("aapt2LinkMs", it) }
			}
		}
	}

	override fun onRebaseline(
		isSuccess: Boolean,
		durationMillis: Long,
	) {
		events.append("rebaseline") {
			put("ok", isSuccess)
			put("durationMillis", durationMillis)
		}
	}

	override fun onInvalidation(reason: InvalidationReason) {
		events.append("invalidation") {
			// reason is an enum; its constant name is the analogue of the sealed-type
			// "simple name" used for route/outcome above.
			put("reason", reason.name)
		}
	}
}
