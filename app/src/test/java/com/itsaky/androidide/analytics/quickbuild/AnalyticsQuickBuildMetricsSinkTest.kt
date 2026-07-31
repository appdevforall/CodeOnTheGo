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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Robolectric only for the real [android.os.Bundle] the parameter-cap test measures. */
@RunWith(RobolectricTestRunner::class)
class AnalyticsQuickBuildMetricsSinkTest {
	@get:Rule
	val tempDir = TemporaryFolder()

	private val tracked = mutableListOf<Metric>()
	private val analytics: IAnalyticsManager =
		mockk {
			every { trackMetric(capture(tracked)) } returns Unit
		}

	private var nowMs = 1_000L

	private fun sink(moduleCount: () -> Int? = { null }) =
		AnalyticsQuickBuildMetricsSink(
			analytics = analytics,
			projectPath = { "/projects/demo" },
			moduleCount = moduleCount,
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
	fun `started metric forwards the project's Android module count`() {
		sink(moduleCount = { 3 }).onBuildStarted(7, BuildRoute.CodeOnly, ChangedFiles.Known(emptySet()))

		val metric = tracked.single() as QuickBuildStartedMetric
		assertThat(metric.moduleCount).isEqualTo(3)
		assertThat(metric.asBundle().getInt("module_count")).isEqualTo(3)
	}

	@Test
	fun `a null module count is omitted from the bundle rather than sent as zero`() {
		sink().onBuildStarted(7, BuildRoute.CodeOnly, ChangedFiles.Known(emptySet()))

		val metric = tracked.single() as QuickBuildStartedMetric
		assertThat(metric.moduleCount).isNull()
		assertThat(metric.asBundle().containsKey("module_count")).isFalse()
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
	fun `reload timeline maps to the reload-timing event with the full loop and per-stage split`() {
		val sink = sink()
		sink.onSessionStarted()
		// gen 42, trigger 1000, compileDone 1600, deploySent 1650, reloadLive 1720
		sink.onReloadTimeline(
			org.appdevforall.cotg.quickbuild.domain
				.E2eTimeline(generation = 42, trigger = 1000, compileDone = 1600, deploySent = 1650, reloadLive = 1720),
		)

		val metric = tracked.single() as QuickBuildReloadTimingMetric
		assertThat(metric.eventName).isEqualTo("quick_build_reload_timing")
		assertThat(metric.generation).isEqualTo(42)
		assertThat(metric.totalMs).isEqualTo(720) // user-perceived save->live
		assertThat(metric.compileMs).isEqualTo(600)
		assertThat(metric.stageMs).isEqualTo(50)
		assertThat(metric.reloadMs).isEqualTo(70)
		assertThat(metric.projectHash).isEqualTo("/projects/demo".hashCode().toLong())
	}

	@Test
	fun `reload timeline carries the span breakdown, the residual and the counts`() {
		val sink = sink()
		sink.onSessionStarted()

		sink.onReloadTimeline(richTimeline())

		val metric = tracked.single() as QuickBuildReloadTimingMetric
		assertThat(metric.scanMs).isEqualTo(240)
		assertThat(metric.compileRpcMs).isEqualTo(4_900)
		assertThat(metric.policyMs).isEqualTo(610)
		assertThat(metric.dexRpcMs).isEqualTo(8_800)
		assertThat(metric.relinkRpcMs).isEqualTo(150)
		// 14_720 total - (240+4900+610+8800+150 spans + 20 reload).
		assertThat(metric.unaccountedMs).isEqualTo(0)
		assertThat(metric.javacMs).isEqualTo(3_983)
		assertThat(metric.walkMs).isEqualTo(250) // the two output-tree walks, summed
		assertThat(metric.javaAbiSnapMs).isEqualTo(621)
		assertThat(metric.kotlinCompiled).isEqualTo(0)
		assertThat(metric.changedClasses).isEqualTo(323)
		assertThat(metric.compileOrdinal).isEqualTo(2)
		assertThat(metric.scratchFs).isEqualTo("fuse")
	}

	@Test
	fun `a timeline with no measured spans claims no residual rather than blaming the whole build`() {
		val sink = sink()
		sink.onSessionStarted()

		sink.onReloadTimeline(
			org.appdevforall.cotg.quickbuild.domain
				.E2eTimeline(generation = 42, trigger = 1000, compileDone = 1600, deploySent = 1650, reloadLive = 1720),
		)

		val metric = tracked.single() as QuickBuildReloadTimingMetric
		assertThat(metric.unaccountedMs).isNull()
		assertThat(metric.scanMs).isNull()
		assertThat(metric.compileOrdinal).isNull()
		assertThat(metric.scratchFs).isNull()
	}

	@Test
	fun `the reload-timing bundle stays within Firebase's per-event parameter cap`() {
		// A fully-populated mixed route is the widest row this event can produce, and
		// trackMetric adds `timestamp` on top of asBundle(). Blowing the cap would make
		// Firebase drop parameters silently - the same class of invisible loss this whole
		// event exists to prevent.
		val sink = sink()
		sink.onSessionStarted()
		sink.onReloadTimeline(richTimeline())

		val bundle = (tracked.single() as QuickBuildReloadTimingMetric).asBundle()

		assertThat(bundle.size()).isLessThan(QuickBuildReloadTimingMetric.MAX_EVENT_PARAMS)
	}

	@Test
	fun `the reload-timing bundle omits every unreported field`() {
		val sink = sink()
		sink.onSessionStarted()
		sink.onReloadTimeline(
			org.appdevforall.cotg.quickbuild.domain
				.E2eTimeline(generation = 1, trigger = 0, compileDone = 10, deploySent = 12, reloadLive = 20),
		)

		val bundle = (tracked.single() as QuickBuildReloadTimingMetric).asBundle()

		assertThat(bundle.containsKey("total_ms")).isTrue()
		assertThat(bundle.containsKey("unaccounted_ms")).isFalse()
		assertThat(bundle.containsKey("scratch_fs")).isFalse()
		assertThat(bundle.containsKey("kotlin_ms")).isFalse()
	}

	/**
	 * A warm mixed-route edit with every field populated, shaped after the sora-editor-full
	 * device rows (ADFA-4128 deep-dive): the spans reconcile to the total exactly.
	 */
	private fun richTimeline() =
		org.appdevforall.cotg.quickbuild.domain.E2eTimeline(
			generation = 9,
			trigger = 0,
			compileDone = 14_700,
			deploySent = 14_700,
			reloadLive = 14_720,
			steps =
				org.appdevforall.cotg.quickbuild.domain.E2eTimeline.StepTimings(
					kotlinMillis = 659,
					javaMillis = 3_983,
					stripMillis = 5_492,
					d8Millis = 3_104,
					aapt2CompileMillis = 60,
					aapt2LinkMillis = 80,
					preSnapMillis = 120,
					postSnapMillis = 130,
					javaAbiSnapMillis = 621,
				),
			spans =
				org.appdevforall.cotg.quickbuild.domain.E2eTimeline.HostSpans(
					scanMillis = 240,
					compileRpcMillis = 4_900,
					policyMillis = 610,
					dexRpcMillis = 8_800,
					relinkRpcMillis = 150,
				),
			counts =
				org.appdevforall.cotg.quickbuild.domain.E2eTimeline.BuildCounts(
					allSources = 292,
					kotlinCompiled = 0,
					javaSources = 218,
					changedClasses = 323,
					classFiles = 464,
					classBytes = 1_530_112,
					compileOrdinal = 2,
				),
			scratchFsType = "fuse",
		)

	@Test
	fun `reload timeline shares the in-flight session id so it joins to the completed event`() {
		val sink = sink()
		sink.onSessionStarted()
		sink.onBuildStarted(1, BuildRoute.CodeOnly, ChangedFiles.Known(emptySet()))
		sink.onReloadTimeline(
			org.appdevforall.cotg.quickbuild.domain
				.E2eTimeline(generation = 1, trigger = 0, compileDone = 10, deploySent = 12, reloadLive = 20),
		)

		val started = tracked[0] as QuickBuildStartedMetric
		val timing = tracked[1] as QuickBuildReloadTimingMetric
		assertThat(timing.qbSessionId).isEqualTo(started.qbSessionId)
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
