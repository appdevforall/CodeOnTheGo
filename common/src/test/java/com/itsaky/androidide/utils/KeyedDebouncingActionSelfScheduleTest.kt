package com.itsaky.androidide.utils

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

/**
 * Scheduling the key an action is *currently running for* cancels that run.
 *
 * The worker races `actionJob.onJoin` against `channel.onReceive`, so a send from inside the action
 * is indistinguishable from a newer key arriving: the receive wins, the in-flight job is cancelled,
 * and the key is re-sent. `KotlinDiagnosticProvider` relies on both halves of that - it reschedules
 * from inside its own action and expects the analysis it just discarded to run again.
 */
class KeyedDebouncingActionSelfScheduleTest {
	private companion object {
		const val SECOND_RUN_TIMEOUT_SECONDS = 5L
	}

	@Test
	fun `scheduling from inside the action cancels that run and re-runs it`() =
		runBlocking {
			val runs = AtomicInteger(0)
			val firstRunFinished = AtomicBoolean(false)
			val secondRunStarted = CountDownLatch(1)
			lateinit var debouncer: KeyedDebouncingAction<String>

			debouncer =
				KeyedDebouncingAction(
					scope = CoroutineScope(SupervisorJob()),
					debounceDuration = 20.milliseconds,
					action = { key, _ ->
						if (runs.incrementAndGet() == 1) {
							debouncer.schedule(key)
							// A suspension point is where the cancellation from the self-send takes effect.
							delay(200)
							firstRunFinished.set(true)
						} else {
							secondRunStarted.countDown()
						}
					},
				)

			debouncer.schedule("k")

			assertThat(secondRunStarted.await(SECOND_RUN_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue()
			assertThat(firstRunFinished.get()).isFalse()
			debouncer.cancelAll()
		}
}
