package com.itsaky.androidide.quickbuild

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.appdevforall.cotg.quickbuild.domain.InvalidationReason
import org.appdevforall.cotg.quickbuild.domain.QuickBuildSessionState
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
class BenchStateRecorderTest {
	@get:Rule
	val tempDir = TemporaryFolder()

	private lateinit var file: File
	private lateinit var recorder: BenchStateRecorder

	@Before
	fun setup() {
		file = File(tempDir.root, "bench-events.jsonl")
		recorder = BenchStateRecorder(BenchEventsFile(file) { 0L })
	}

	private fun objects() = file.readLines().map { JSONObject(it) }

	@Test
	fun `record maps state to its simple name and includes generation only where carried`() {
		recorder.record(QuickBuildSessionState.Idle)
		recorder.record(QuickBuildSessionState.Prewarming())
		recorder.record(QuickBuildSessionState.Provisioning)
		recorder.record(QuickBuildSessionState.Ready(generation = 5))
		recorder.record(QuickBuildSessionState.Building(deployedGeneration = 5))
		recorder.record(QuickBuildSessionState.Deployed(generation = 6, buildDurationMillis = 100))
		recorder.record(QuickBuildSessionState.Invalidated(InvalidationReason.MANIFEST_CHANGED, deployedGeneration = 6))
		recorder.record(QuickBuildSessionState.Degraded(deployedGeneration = 6))

		val o = objects()
		assertThat(o.map { it.getString("state") })
			.containsExactly(
				"Idle",
				"Prewarming",
				"Provisioning",
				"Ready",
				"Building",
				"Deployed",
				"Invalidated",
				"Degraded",
			).inOrder()

		// No generation on the pre-live states.
		assertThat(o[0].has("generation")).isFalse()
		assertThat(o[1].has("generation")).isFalse()
		assertThat(o[2].has("generation")).isFalse()
		// Generation present (and correct) on each state that carries one.
		assertThat(o[3].getLong("generation")).isEqualTo(5)
		assertThat(o[4].getLong("generation")).isEqualTo(5)
		assertThat(o[5].getLong("generation")).isEqualTo(6)
		assertThat(o[6].getLong("generation")).isEqualTo(6)
		assertThat(o[7].getLong("generation")).isEqualTo(6)
	}

	@OptIn(ExperimentalCoroutinesApi::class)
	@Test
	fun `attach writes one line per state-flow change, deduped by StateFlow`() =
		runTest {
			// Unconfined so the collector runs eagerly on attach (emits Idle) and on each
			// value assignment, making the sequence deterministic without advancing time.
			val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
			val flow = MutableStateFlow<QuickBuildSessionState>(QuickBuildSessionState.Idle)
			recorder.attach(flow, scope)

			flow.value = QuickBuildSessionState.Provisioning
			flow.value = QuickBuildSessionState.Ready(generation = 2)
			flow.value = QuickBuildSessionState.Building(deployedGeneration = 2)
			flow.value = QuickBuildSessionState.Deployed(generation = 3, buildDurationMillis = 50)
			scope.cancel()

			val o = objects()
			assertThat(o.map { it.getString("state") })
				.containsExactly("Idle", "Provisioning", "Ready", "Building", "Deployed")
				.inOrder()
			assertThat(o[2].getLong("generation")).isEqualTo(2)
			assertThat(o[3].getLong("generation")).isEqualTo(2)
			assertThat(o[4].getLong("generation")).isEqualTo(3)
		}
}
