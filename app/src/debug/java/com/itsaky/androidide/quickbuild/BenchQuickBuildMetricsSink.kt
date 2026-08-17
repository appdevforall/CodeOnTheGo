package com.itsaky.androidide.quickbuild

import org.appdevforall.cotg.quickbuild.domain.ChangedFiles
import org.appdevforall.cotg.quickbuild.domain.classify.BuildRoute
import org.appdevforall.cotg.quickbuild.domain.classify.InvalidationReason
import org.appdevforall.cotg.quickbuild.domain.reload.BuildDiagnostic
import org.appdevforall.cotg.quickbuild.domain.reload.BuildOutcome
import org.appdevforall.cotg.quickbuild.domain.telemetry.E2eTimeline
import org.appdevforall.cotg.quickbuild.domain.telemetry.QuickBuildMetricsSink

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
			put("route", route.wireName())
		}
	}

	override fun onBuildFinished(
		buildId: Long,
		outcome: BuildOutcome,
	) {
		events.append("build_finished") {
			put("buildId", buildId)
			put("outcome", outcome.wireName())
			// Additive: the outcome name alone cannot tell two failures of the same kind
			// apart, and a gapped run's logcat tail rarely still covers the failure.
			outcome.failureDetail()?.let { put("detail", it) }
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
				steps.preSnapMillis?.let { put("preSnapMs", it) }
				steps.postSnapMillis?.let { put("postSnapMs", it) }
				steps.javaAbiSnapMillis?.let { put("javaAbiSnapMs", it) }
			}
			// The host spans that partition the build, and the residual they leave. The
			// residual is the point: it is what a future un-timed step shows up in.
			timeline.spans?.let { spans ->
				spans.queueMillis?.let { put("queueMs", it) }
				spans.scanMillis?.let { put("scanMs", it) }
				spans.compileRpcMillis?.let { put("compileRpcMs", it) }
				spans.policyMillis?.let { put("policyMs", it) }
				spans.dexRpcMillis?.let { put("dexRpcMs", it) }
				spans.relinkRpcMillis?.let { put("relinkRpcMs", it) }
				put("accountedMs", timeline.accountedMillis)
				put("unaccountedMs", timeline.unaccountedMillis)
			}
			timeline.counts?.let { counts ->
				counts.allSources?.let { put("nAllSources", it) }
				counts.kotlinCompiled?.let { put("nKotlinCompiled", it) }
				counts.javaSources?.let { put("nJavaSources", it) }
				counts.changedClasses?.let { put("nChangedClasses", it) }
				counts.classFiles?.let { put("nClassFiles", it) }
				counts.classBytes?.let { put("classBytes", it) }
				counts.compileOrdinal?.let { put("compileOrdinal", it) }
			}
			timeline.scratchFsType?.let { put("scratchFs", it) }
		}
	}

	override fun onProxyAppRebuild(
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
			put("reason", reason.wireName())
		}
	}

	// The wireName() maps below pin the serialized values as an explicit contract,
	// decoupled from the Kotlin identifiers. The benchmark harness string-compares these
	// literals (e.g. run_e2e_bench.py reads "RequiresRebaseline"), and historical
	// .events.jsonl files carry them - so an identifier rename must NOT change any
	// string here. Same pattern as AnalyticsQuickBuildMetricsSink.metricName().

	private fun BuildRoute.wireName(): String =
		when (this) {
			is BuildRoute.FullGradleBuild -> "FullGradleBuild"
			BuildRoute.ResourcesOnly -> "ResourcesOnly"
			BuildRoute.AssetsOnly -> "AssetsOnly"
			BuildRoute.CodeOnly -> "CodeOnly"
			BuildRoute.CodeAndResources -> "CodeAndResources"
			BuildRoute.NoOp -> "NoOp"
			BuildRoute.WarmCompile -> "Seed"
		}

	private fun BuildOutcome.wireName(): String =
		when (this) {
			is BuildOutcome.Success -> "Success"
			is BuildOutcome.RequiresProxyAppRebuild -> "RequiresRebaseline"
			is BuildOutcome.CompileError -> "CompileError"
			is BuildOutcome.DeployFailure -> "DeployFailure"
			is BuildOutcome.InfrastructureFailure -> "InfrastructureFailure"
		}

	/**
	 * The failing outcome's own text, or null when it succeeded. Free-form: unlike
	 * [wireName] nothing string-compares this, so the wording may change.
	 */
	private fun BuildOutcome.failureDetail(): String? =
		when (this) {
			is BuildOutcome.Success -> null
			is BuildOutcome.RequiresProxyAppRebuild -> detail
			is BuildOutcome.CompileError -> diagnostics.firstOrNull { it.severity == BuildDiagnostic.Severity.ERROR }?.message
			is BuildOutcome.DeployFailure -> message
			is BuildOutcome.InfrastructureFailure -> message
		}

	private fun InvalidationReason.wireName(): String =
		when (this) {
			InvalidationReason.MANIFEST_CHANGED -> "MANIFEST_CHANGED"
			InvalidationReason.GRADLE_CONFIG_CHANGED -> "GRADLE_CONFIG_CHANGED"
			InvalidationReason.UNSUPPORTED_FILE_CHANGED -> "UNSUPPORTED_FILE_CHANGED"
			InvalidationReason.NON_APP_MODULE_SOURCE_CHANGED -> "NON_APP_MODULE_SOURCE_CHANGED"
			InvalidationReason.EXTERNAL_FULL_BUILD -> "EXTERNAL_FULL_BUILD"
			InvalidationReason.ANNOTATION_PROCESSOR_INPUT_CHANGED -> "ANNOTATION_PROCESSOR_INPUT_CHANGED"
			InvalidationReason.OUTDATED_BASELINE -> "OUTDATED_BASELINE"
			InvalidationReason.RELOAD_PIPELINE_FAILED -> "RELOAD_PIPELINE_FAILED"
			InvalidationReason.INSTALL_NOT_CONFIRMED -> "INSTALL_NOT_CONFIRMED"
		}
}
