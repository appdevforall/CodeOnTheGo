package com.itsaky.androidide.assets.addons

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.itsaky.androidide.utils.TrackedFlashbar
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.coroutines.EmptyCoroutineContext

/**
 * The bundled plugin failures stay unreported until the user dismisses their bar (F30): a screen
 * recreated by a font scale change took the bar away, and the failures, already marked when the bar
 * appeared, never showed again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BundledFailureReportTest {
	@get:Rule
	val tempFolder = TemporaryFolder()

	private lateinit var record: BundledAddonsRecord
	private val bars = ArrayList<FakeBar>()

	private class FakeBar(
		val failures: List<BundledPluginFailure>,
	) : TrackedFlashbar {
		val dismissed = CompletableDeferred<Unit>()

		override suspend fun awaitShown() = true

		override suspend fun awaitDismissed() = dismissed.await()
	}

	@Before
	fun setUp() {
		record = BundledAddonsRecord(File(tempFolder.root, "bundled_addons.properties"))
		record.write(BundledAddonsState("fp", listOf("com.example.one", "com.example.two")))
		record.recordFailures(mapOf("com.example.one" to "boom"))
		bars.clear()
	}

	private fun TestScope.startReport(): Job {
		val job = launch { record.reportFailures(EmptyCoroutineContext) { FakeBar(it).also(bars::add) } }
		runCurrent()
		return job
	}

	@Test
	fun `the failures stay unreported until the user dismisses the bar, so the next screen shows them again`() =
		runTest {
			val report = startReport()
			assertThat(bars).hasSize(1)
			assertWithMessage("failures marked reported while the bar is still up")
				.that(record.unreportedFailures())
				.containsExactly(BundledPluginFailure("com.example.one", "boom"))

			// The screen goes away with the bar up: its scope ends, and no dismiss callback fires.
			report.cancel()
			runCurrent()
			assertThat(record.unreportedFailures()).containsExactly(BundledPluginFailure("com.example.one", "boom"))

			val next = startReport()
			assertThat(bars).hasSize(2)
			assertThat(bars[1].failures).containsExactly(BundledPluginFailure("com.example.one", "boom"))
			next.cancel()
		}

	@Test
	fun `the report lasts while the bar is up, and dismissing it marks the failures reported`() =
		runTest {
			val report = startReport()
			assertWithMessage("report ended before the user dismissed the bar").that(report.isActive).isTrue()

			bars.single().dismissed.complete(Unit)
			runCurrent()

			assertThat(report.isCompleted).isTrue()
			assertThat(record.unreportedFailures()).isEmpty()
			startReport()
			assertWithMessage("a bar shown again after the user dismissed it").that(bars).hasSize(1)
		}

	@Test
	fun `a failure recorded while the bar is up stays unreported`() =
		runTest {
			startReport()
			record.recordFailures(mapOf("com.example.two" to "bang"))

			bars.single().dismissed.complete(Unit)
			runCurrent()

			assertThat(record.unreportedFailures()).containsExactly(BundledPluginFailure("com.example.two", "bang"))
		}

	@Test
	fun `no unreported failure shows no bar`() =
		runTest {
			record.markReported(record.unreportedFailures())

			val report = startReport()

			assertThat(bars).isEmpty()
			assertThat(report.isCompleted).isTrue()
		}
}
