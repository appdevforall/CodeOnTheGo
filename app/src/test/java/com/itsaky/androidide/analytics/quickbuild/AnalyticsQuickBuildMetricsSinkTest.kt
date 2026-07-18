package com.itsaky.androidide.analytics.quickbuild

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.analytics.IAnalyticsManager
import com.itsaky.androidide.analytics.Metric
import io.mockk.every
import io.mockk.mockk
import org.appdevforall.cotg.quickbuild.domain.BuildDiagnostic
import org.appdevforall.cotg.quickbuild.domain.BuildOutcome
import org.appdevforall.cotg.quickbuild.domain.BuildRoute
import org.appdevforall.cotg.quickbuild.domain.ChangedFiles
import org.appdevforall.cotg.quickbuild.domain.InvalidationReason
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AnalyticsQuickBuildMetricsSinkTest {
	@get:Rule
	val tempDir = TemporaryFolder()

	private val tracked = mutableListOf<Metric>()
	private val analytics: IAnalyticsManager =
		mockk {
			every { trackMetric(capture(tracked)) } returns Unit
		}

	private var nowMs = 1_000L

	private fun sink() =
		AnalyticsQuickBuildMetricsSink(
			analytics = analytics,
			projectPath = { "/projects/demo" },
			now = { nowMs },
		)

	@Test
	fun `started metric carries route, file count and kb for a known changed-set`() {
		val a = tempDir.newFile("A.kt").apply { writeBytes(ByteArray(2048)) }
		val b = tempDir.newFile("B.kt").apply { writeBytes(ByteArray(1024)) }

		sink().onBuildStarted(7, BuildRoute.CodeAndResources, ChangedFiles.Known(setOf(a, b)))

		val metric = tracked.single() as QuickBuildStartedMetric
		assertThat(metric.eventName).isEqualTo("quick_build_started")
		assertThat(metric.route).isEqualTo("code_and_resources")
		assertThat(metric.changedFiles).isEqualTo(2)
		assertThat(metric.changedKb).isEqualTo(3)
		assertThat(metric.projectHash).isEqualTo("/projects/demo".hashCode().toLong())
	}

	@Test
	fun `started metric breaks the changed-set down by file type`() {
		val kt = tempDir.newFile("Main.kt")
		val java = tempDir.newFile("Util.java")
		val layout = tempDir.newFolder("res", "layout").let { File(it, "main.xml").apply { createNewFile() } }
		val asset =
			tempDir.newFolder("assets", "data").let {
				// An asset keeps its own extension; the path is what classifies it.
				File(it, "levels.xml").apply { createNewFile() }
			}
		val other = tempDir.newFile("notes.txt")

		sink().onBuildStarted(
			7,
			BuildRoute.CodeAndResources,
			ChangedFiles.Known(setOf(kt, java, layout, asset, other)),
		)

		val metric = tracked.single() as QuickBuildStartedMetric
		assertThat(metric.changedKotlin).isEqualTo(1)
		assertThat(metric.changedJava).isEqualTo(1)
		assertThat(metric.changedXml).isEqualTo(1)
		assertThat(metric.changedAssets).isEqualTo(1)
		assertThat(metric.changedOther).isEqualTo(1)
	}

	@Test
	fun `an unknown changed-set reports no size or mix fields`() {
		sink().onBuildStarted(7, BuildRoute.CodeOnly, ChangedFiles.Unknown)

		val metric = tracked.single() as QuickBuildStartedMetric
		assertThat(metric.changedFiles).isNull()
		assertThat(metric.changedKb).isNull()
		assertThat(metric.changedKotlin).isNull()
	}

	@Test
	fun `success uses the executor-measured duration and generation`() {
		val sink = sink()
		sink.onBuildStarted(3, BuildRoute.CodeOnly, ChangedFiles.Known(emptySet()))
		nowMs += 5_000

		sink.onBuildFinished(3, BuildOutcome.Success(generation = 42, durationMillis = 900))

		val metric = tracked.last() as QuickBuildCompletedMetric
		assertThat(metric.isSuccess).isTrue()
		assertThat(metric.outcome).isEqualTo("deployed")
		assertThat(metric.durationMs).isEqualTo(900)
		assertThat(metric.generation).isEqualTo(42)
		// Route rides on the completed event so duration-by-change-type needs no join.
		assertThat(metric.route).isEqualTo("code_only")
	}

	@Test
	fun `a compile error falls back to wall-clock duration and counts diagnostics`() {
		val sink = sink()
		sink.onBuildStarted(3, BuildRoute.CodeOnly, ChangedFiles.Known(emptySet()))
		nowMs += 1_234

		sink.onBuildFinished(
			3,
			BuildOutcome.CompileError(
				listOf(
					BuildDiagnostic(BuildDiagnostic.Severity.ERROR, "boom"),
					BuildDiagnostic(BuildDiagnostic.Severity.ERROR, "boom too"),
				),
			),
		)

		val metric = tracked.last() as QuickBuildCompletedMetric
		assertThat(metric.isSuccess).isFalse()
		assertThat(metric.outcome).isEqualTo("compile_error")
		assertThat(metric.durationMs).isEqualTo(1_234)
		assertThat(metric.generation).isNull()
		assertThat(metric.diagnosticsCount).isEqualTo(2)
	}

	@Test
	fun `session id ties started to completed and rotates per session`() {
		val sink = sink()
		sink.onSessionStarted()
		sink.onBuildStarted(1, BuildRoute.CodeOnly, ChangedFiles.Known(emptySet()))
		sink.onBuildFinished(1, BuildOutcome.Success(generation = 1, durationMillis = 10))

		val started = tracked[0] as QuickBuildStartedMetric
		val completed = tracked[1] as QuickBuildCompletedMetric
		// (qb_session_id, qb_build_id) is the join key, same shape as Gradle's BuildId.
		assertThat(completed.qbSessionId).isEqualTo(started.qbSessionId)
		assertThat(completed.buildId).isEqualTo(started.buildId)

		sink.onSessionStarted()
		sink.onBuildStarted(1, BuildRoute.CodeOnly, ChangedFiles.Known(emptySet()))
		val nextSession = tracked[2] as QuickBuildStartedMetric
		// Build ids restart per session; the rotated session id keeps the pair unique.
		assertThat(nextSession.buildId).isEqualTo(started.buildId)
		assertThat(nextSession.qbSessionId).isNotEqualTo(started.qbSessionId)
	}

	@Test
	fun `invalidation and rebaseline map to low-cardinality events`() {
		val sink = sink()
		sink.onInvalidation(InvalidationReason.MANIFEST_CHANGED)
		sink.onRebaseline(isSuccess = true, durationMillis = 7_500)

		val invalidated = tracked[0] as QuickBuildInvalidatedMetric
		assertThat(invalidated.eventName).isEqualTo("quick_build_invalidated")
		assertThat(invalidated.reason).isEqualTo("manifest_changed")

		val rebaseline = tracked[1] as QuickBuildRebaselineMetric
		assertThat(rebaseline.eventName).isEqualTo("quick_build_rebaseline")
		assertThat(rebaseline.isSuccess).isTrue()
		assertThat(rebaseline.durationMs).isEqualTo(7_500)
	}
}
