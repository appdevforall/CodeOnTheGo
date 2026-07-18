package com.itsaky.androidide.analytics.quickbuild

import com.itsaky.androidide.analytics.IAnalyticsManager
import org.appdevforall.cotg.quickbuild.domain.BuildOutcome
import org.appdevforall.cotg.quickbuild.domain.BuildRoute
import org.appdevforall.cotg.quickbuild.domain.ChangedFiles
import org.appdevforall.cotg.quickbuild.domain.InvalidationReason
import org.appdevforall.cotg.quickbuild.domain.QuickBuildMetricsSink
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The app's [QuickBuildMetricsSink]: forwards the quick-build domain's run statistics to
 * Firebase through [IAnalyticsManager] (David's tracking ask, scoped to what is already
 * in RAM or a cheap stat call). Runs on the session dispatcher, never on Main; the
 * session manager guards every call, so this class may stay lean.
 *
 * Failure durations are wall-clock measured here (only [BuildOutcome.Success] carries an
 * executor-measured duration); at most one build is in flight, so the map stays tiny.
 */
class AnalyticsQuickBuildMetricsSink(
	private val analytics: IAnalyticsManager,
	private val projectPath: () -> String,
	private val now: () -> Long = System::currentTimeMillis,
) : QuickBuildMetricsSink {
	private data class InFlight(
		val startedAtMs: Long,
		val route: String,
	)

	private val inFlight = ConcurrentHashMap<Long, InFlight>()

	/**
	 * Same shape as GradleBuildService's BuildId(buildSessionId, counter): a UUID scoping
	 * the per-session build counter. Rotated per quick-build session (not per process)
	 * because the orchestrator's build ids restart at 1 with every session.
	 */
	@Volatile
	private var sessionId: String = newSessionId()

	override fun onSessionStarted() {
		sessionId = newSessionId()
	}

	override fun onBuildStarted(
		buildId: Long,
		route: BuildRoute,
		changes: ChangedFiles,
	) {
		val routeName = route.metricName()
		inFlight[buildId] = InFlight(now(), routeName)
		val known = changes as? ChangedFiles.Known
		val mix = known?.files?.let { FileTypeMix.of(it) }
		analytics.trackMetric(
			QuickBuildStartedMetric(
				qbSessionId = sessionId,
				buildId = buildId,
				route = routeName,
				changedFiles = known?.files?.size,
				changedKb = known?.files?.sumOf { it.length() }?.let { it / 1024 },
				changedKotlin = mix?.kotlin,
				changedJava = mix?.java,
				changedXml = mix?.xml,
				changedAssets = mix?.assets,
				changedOther = mix?.other,
				projectHash = projectHash(),
			),
		)
	}

	override fun onBuildFinished(
		buildId: Long,
		outcome: BuildOutcome,
	) {
		val started = inFlight.remove(buildId)
		val elapsedMs = started?.let { now() - it.startedAtMs }
		analytics.trackMetric(
			QuickBuildCompletedMetric(
				qbSessionId = sessionId,
				buildId = buildId,
				route = started?.route,
				outcome = outcome.metricName(),
				isSuccess = outcome is BuildOutcome.Success,
				durationMs = (outcome as? BuildOutcome.Success)?.durationMillis ?: elapsedMs ?: -1,
				generation = (outcome as? BuildOutcome.Success)?.generation,
				diagnosticsCount = (outcome as? BuildOutcome.CompileError)?.diagnostics?.size,
				projectHash = projectHash(),
			),
		)
	}

	override fun onInvalidation(reason: InvalidationReason) {
		analytics.trackMetric(
			QuickBuildInvalidatedMetric(
				qbSessionId = sessionId,
				reason = reason.name.lowercase(),
				projectHash = projectHash(),
			),
		)
	}

	override fun onRebaseline(
		isSuccess: Boolean,
		durationMillis: Long,
	) {
		analytics.trackMetric(
			QuickBuildRebaselineMetric(
				qbSessionId = sessionId,
				isSuccess = isSuccess,
				durationMs = durationMillis,
				projectHash = projectHash(),
			),
		)
	}

	private fun projectHash(): Long = projectPath().hashCode().toLong()

	private fun newSessionId(): String =
		java.util.UUID
			.randomUUID()
			.toString()

	/** The change-type mix David's tuning question needs: what do users actually edit? */
	private data class FileTypeMix(
		val kotlin: Int,
		val java: Int,
		val xml: Int,
		val assets: Int,
		val other: Int,
	) {
		companion object {
			fun of(files: Set<File>): FileTypeMix {
				var kt = 0
				var java = 0
				var xml = 0
				var assets = 0
				var other = 0
				files.forEach { file ->
					when {
						file.path.contains("${File.separator}assets${File.separator}") -> assets++
						file.extension == "kt" -> kt++
						file.extension == "java" -> java++
						file.extension == "xml" -> xml++
						else -> other++
					}
				}
				return FileTypeMix(kt, java, xml, assets, other)
			}
		}
	}

	private fun BuildRoute.metricName(): String =
		when (this) {
			is BuildRoute.FullGradleBuild -> "full_gradle"
			BuildRoute.ResourcesOnly -> "resources_only"
			BuildRoute.AssetsOnly -> "assets_only"
			BuildRoute.CodeOnly -> "code_only"
			BuildRoute.CodeAndResources -> "code_and_resources"
			BuildRoute.NoOp -> "no_op"
		}

	private fun BuildOutcome.metricName(): String =
		when (this) {
			is BuildOutcome.Success -> "deployed"
			is BuildOutcome.CompileError -> "compile_error"
			is BuildOutcome.DeployFailure -> "deploy_failure"
			is BuildOutcome.InfrastructureFailure -> "infrastructure"
		}
}
