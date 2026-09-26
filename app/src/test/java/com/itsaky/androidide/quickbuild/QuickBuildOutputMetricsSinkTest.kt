package com.itsaky.androidide.quickbuild

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.mockk
import org.appdevforall.cotg.quickbuild.domain.ChangedFiles
import org.appdevforall.cotg.quickbuild.domain.classify.BuildRoute
import org.appdevforall.cotg.quickbuild.domain.classify.InvalidationReason
import org.appdevforall.cotg.quickbuild.domain.reload.BuildOutcome
import org.appdevforall.cotg.quickbuild.domain.telemetry.E2eTimeline
import org.appdevforall.cotg.quickbuild.domain.telemetry.QuickBuildMetricsSink
import org.junit.Test

/**
 * Pins which half of the metrics port reaches the Build Output pane.
 *
 * [E2eTimeline] is the only event on the port with a per-stage split worth reading in a log;
 * the other five are statistics. The defect this suite exists to stop is a later hand wiring
 * one of those five to the narrator - the pane is what a user reads after every save, so a
 * counter landing there is a product regression no compiler can see. The forwarding half is
 * pinned in the same suite so the class cannot quietly become an all-Unit no-op instead.
 */
class QuickBuildOutputMetricsSinkTest {
	private val narrated = mutableListOf<E2eTimeline>()

	private val narrator =
		mockk<QuickBuildOutputNarrator>().also { mock ->
			every { mock.narrate(any()) } answers { narrated += firstArg<E2eTimeline>() }
		}

	private val sink: QuickBuildMetricsSink = QuickBuildOutputMetricsSink(narrator)

	private val timeline =
		E2eTimeline(generation = 7, trigger = 100, compileDone = 220, deploySent = 260, reloadLive = 410)

	@Test
	fun `the reload timeline reaches the narrator as the same object, so the pane renders the build's own stamps`() {
		sink.onReloadTimeline(timeline)

		assertThat(narrated).hasSize(1)
		assertThat(narrated.single()).isSameInstanceAs(timeline)
	}

	@Test
	fun `no statistics-only callback reaches the narrator - each of the five is checked by name`() {
		val statisticsOnly: List<Pair<String, () -> Unit>> =
			listOf(
				"onSessionStarted" to { sink.onSessionStarted() },
				"onBuildStarted" to {
					sink.onBuildStarted(1L, BuildRoute.CodeOnly, ChangedFiles.Known(emptySet()))
				},
				"onBuildFinished" to { sink.onBuildFinished(1L, BuildOutcome.Success(1, 10)) },
				"onInvalidation" to { sink.onInvalidation(InvalidationReason.MANIFEST_CHANGED) },
				"onProxyAppRebuild" to {
					sink.onProxyAppRebuild(
						isSuccess = true,
						durationMillis = 42,
						relaunchOk = true,
						toRunningMillis = 99,
					)
				},
			)

		statisticsOnly.forEach { (name, callback) ->
			// Cleared per callback so the failure names the one that leaked, not the first.
			narrated.clear()
			callback()

			assertWithMessage("$name must not narrate into the Build Output pane")
				.that(narrated)
				.isEmpty()
		}
	}
}
