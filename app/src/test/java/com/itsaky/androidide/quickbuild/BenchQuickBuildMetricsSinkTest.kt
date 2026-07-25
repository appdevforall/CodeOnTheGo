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
