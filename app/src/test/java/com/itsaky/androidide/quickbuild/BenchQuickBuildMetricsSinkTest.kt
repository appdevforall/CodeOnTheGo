package com.itsaky.androidide.quickbuild

import com.google.common.truth.Truth.assertThat
import org.appdevforall.cotg.quickbuild.domain.BuildOutcome
import org.appdevforall.cotg.quickbuild.domain.BuildRoute
import org.appdevforall.cotg.quickbuild.domain.ChangedFiles
import org.appdevforall.cotg.quickbuild.domain.E2eTimeline
import org.appdevforall.cotg.quickbuild.domain.InvalidationReason
import org.json.JSONObject
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Robolectric for the real `org.json` (see [BenchEventsFileTest]). */
@RunWith(RobolectricTestRunner::class)
class BenchQuickBuildMetricsSinkTest {
	@get:Rule
	val tempDir = TemporaryFolder()

	private lateinit var file: File
	private lateinit var sink: BenchQuickBuildMetricsSink

	@Before
	fun setup() {
		file = File(tempDir.root, "bench-events.jsonl")
		sink = BenchQuickBuildMetricsSink(BenchEventsFile(file) { 42L })
	}

	private fun last(): JSONObject = JSONObject(file.readLines().last())

	@Test
	fun `session_started carries only the envelope`() {
		sink.onSessionStarted()

		val o = last()
		assertThat(o.getString("event")).isEqualTo("session_started")
		assertThat(o.getInt("v")).isEqualTo(1)
		assertThat(o.getLong("wallMs")).isEqualTo(42)
	}

	@Test
	fun `build_started carries buildId and the route simple name`() {
		sink.onBuildStarted(7, BuildRoute.CodeAndResources, ChangedFiles.Known(emptySet()))

		val o = last()
		assertThat(o.getString("event")).isEqualTo("build_started")
		assertThat(o.getLong("buildId")).isEqualTo(7)
		assertThat(o.getString("route")).isEqualTo("CodeAndResources")
	}

	@Test
	fun `build_finished carries buildId and the outcome simple name`() {
		sink.onBuildFinished(7, BuildOutcome.Success(generation = 3, durationMillis = 100))

		val o = last()
		assertThat(o.getString("event")).isEqualTo("build_finished")
		assertThat(o.getLong("buildId")).isEqualTo(7)
		assertThat(o.getString("outcome")).isEqualTo("Success")
	}

	@Test
	fun `reload_timeline carries every timeline field plus derived totalMs`() {
		sink.onReloadTimeline(
			E2eTimeline(generation = 42, trigger = 1_000, compileDone = 1_600, deploySent = 1_650, reloadLive = 1_720),
		)

		val o = last()
		assertThat(o.getString("event")).isEqualTo("reload_timeline")
		assertThat(o.getLong("generation")).isEqualTo(42)
		assertThat(o.getLong("trigger")).isEqualTo(1_000)
		assertThat(o.getLong("compileDone")).isEqualTo(1_600)
		assertThat(o.getLong("deploySent")).isEqualTo(1_650)
		assertThat(o.getLong("reloadLive")).isEqualTo(1_720)
		assertThat(o.getLong("totalMs")).isEqualTo(720)
		// No steps reported: none of the sub-step fields appear.
		assertThat(o.has("kotlinMs")).isFalse()
		assertThat(o.has("d8Ms")).isFalse()
	}

	@Test
	fun `reload_timeline carries reported sub-step timings and omits unreported ones`() {
		sink.onReloadTimeline(
			E2eTimeline(
				generation = 43,
				trigger = 1_000,
				compileDone = 1_600,
				deploySent = 1_650,
				reloadLive = 1_720,
				steps =
					E2eTimeline.StepTimings(
						kotlinMillis = 400,
						javaMillis = null,
						stripMillis = 20,
						d8Millis = 150,
						aapt2CompileMillis = null,
						aapt2LinkMillis = null,
					),
			),
		)

		val o = last()
		assertThat(o.getLong("kotlinMs")).isEqualTo(400)
		assertThat(o.getLong("stripMs")).isEqualTo(20)
		assertThat(o.getLong("d8Ms")).isEqualTo(150)
		assertThat(o.has("javacMs")).isFalse()
		assertThat(o.has("aapt2CompileMs")).isFalse()
		assertThat(o.has("aapt2LinkMs")).isFalse()
	}

	@Test
	fun `reload_timeline carries the host spans, the residual and the daemon counts`() {
		sink.onReloadTimeline(
			E2eTimeline(
				generation = 44,
				trigger = 0,
				compileDone = 14_700,
				deploySent = 14_700,
				reloadLive = 14_720,
				steps =
					E2eTimeline.StepTimings(
						preSnapMillis = 120,
						postSnapMillis = 130,
						javaAbiSnapMillis = 621,
					),
				spans =
					E2eTimeline.HostSpans(
						scanMillis = 240,
						compileRpcMillis = 4_900,
						policyMillis = 610,
						dexRpcMillis = 8_800,
						relinkRpcMillis = 150,
					),
				counts =
					E2eTimeline.BuildCounts(
						allSources = 292,
						kotlinCompiled = 0,
						javaSources = 218,
						changedClasses = 323,
						classFiles = 464,
						classBytes = 1_530_112,
						compileOrdinal = 2,
					),
				scratchFsType = "fuse",
			),
		)

		val o = last()
		assertThat(o.getLong("scanMs")).isEqualTo(240)
		assertThat(o.getLong("compileRpcMs")).isEqualTo(4_900)
		assertThat(o.getLong("policyMs")).isEqualTo(610)
		assertThat(o.getLong("dexRpcMs")).isEqualTo(8_800)
		assertThat(o.getLong("relinkRpcMs")).isEqualTo(150)
		// The spans plus the reload tail cover the whole loop: nothing is hiding.
		assertThat(o.getLong("accountedMs")).isEqualTo(14_720)
		assertThat(o.getLong("unaccountedMs")).isEqualTo(0)
		// The bench event keeps the two walks separate; only the Firebase event sums them.
		assertThat(o.getLong("preSnapMs")).isEqualTo(120)
		assertThat(o.getLong("postSnapMs")).isEqualTo(130)
		assertThat(o.getLong("javaAbiSnapMs")).isEqualTo(621)
		assertThat(o.getLong("nAllSources")).isEqualTo(292)
		assertThat(o.getLong("nKotlinCompiled")).isEqualTo(0)
		assertThat(o.getLong("nJavaSources")).isEqualTo(218)
		assertThat(o.getLong("nChangedClasses")).isEqualTo(323)
		assertThat(o.getLong("nClassFiles")).isEqualTo(464)
		assertThat(o.getLong("classBytes")).isEqualTo(1_530_112)
		assertThat(o.getLong("compileOrdinal")).isEqualTo(2)
		assertThat(o.getString("scratchFs")).isEqualTo("fuse")
	}

	@Test
	fun `reload_timeline omits the residual entirely when no span was measured`() {
		// A pre-instrumentation daemon: reporting unaccountedMs here would read as "the
		// whole build is unexplained" rather than "nothing was measured".
		sink.onReloadTimeline(
			E2eTimeline(generation = 45, trigger = 0, compileDone = 100, deploySent = 110, reloadLive = 120),
		)

		val o = last()
		assertThat(o.has("unaccountedMs")).isFalse()
		assertThat(o.has("accountedMs")).isFalse()
		assertThat(o.has("scanMs")).isFalse()
		assertThat(o.has("scratchFs")).isFalse()
	}

	@Test
	fun `rebaseline carries ok and duration`() {
		sink.onRebaseline(isSuccess = true, durationMillis = 7_500)

		val o = last()
		assertThat(o.getString("event")).isEqualTo("rebaseline")
		assertThat(o.getBoolean("ok")).isTrue()
		assertThat(o.getLong("durationMillis")).isEqualTo(7_500)
	}

	@Test
	fun `invalidation carries the reason name`() {
		sink.onInvalidation(InvalidationReason.MANIFEST_CHANGED)

		val o = last()
		assertThat(o.getString("event")).isEqualTo("invalidation")
		assertThat(o.getString("reason")).isEqualTo("MANIFEST_CHANGED")
	}
}
