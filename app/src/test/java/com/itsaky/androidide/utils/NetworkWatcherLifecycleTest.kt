/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.utils

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the sampling loop's lifecycle (ADFA-5489).
 *
 * The loop spends nearly all of its time in `delay()`, so "stopped" cannot mean "will notice a
 * flag eventually": between the request and the next tick the watcher is still sampling, and a
 * stop followed by a start inside that window used to leave two loops appending to one buffer.
 *
 * Driven on a virtual clock. Waiting on the wall clock instead is what hung the test executor the
 * first time this was attempted.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NetworkWatcherLifecycleTest {
	private fun watcher(
		dispatcher: kotlin.coroutines.CoroutineContext,
		onSample: () -> Unit = {},
	): NetworkUsageWatcher {
		var counter = 0L
		return NetworkUsageWatcher(
			updateInterval = INTERVAL_MS,
			uid = TEST_UID,
			readRxBytes = {
				onSample()
				counter += 100L
				counter
			},
			readTxBytes = { counter },
			coroutineDispatcher = dispatcher,
			mainDispatcher = dispatcher,
		)
	}

	@Test
	fun `stopping inside the sampling interval actually stops sampling`() =
		runTest {
			val dispatcher = StandardTestDispatcher(testScheduler)
			var samples = 0
			val watcher = watcher(dispatcher) { samples++ }

			try {
				watcher.startWatching()
				advanceTimeBy(INTERVAL_MS * 3)
				val whileRunning = samples

				watcher.stopWatching()
				advanceTimeBy(INTERVAL_MS * 5)

				// Cancelling the job rather than waiting for the loop to observe a flag is what makes
				// this exact: nothing is sampled after the stop.
				assertThat(whileRunning).isGreaterThan(0)
				assertThat(samples).isEqualTo(whileRunning)
				assertThat(watcher.isWatching).isFalse()
			} finally {
				// Closed here rather than after the assertions: see the class KDoc.
				watcher.close()
			}
		}

	@Test
	fun `restarting inside the sampling interval does not leave two loops running`() =
		runTest {
			val dispatcher = StandardTestDispatcher(testScheduler)
			var samples = 0
			val watcher = watcher(dispatcher) { samples++ }

			try {
				watcher.startWatching()
				advanceTimeBy(INTERVAL_MS * 2)
				watcher.stopWatching()
				watcher.startWatching()

				val before = samples
				advanceTimeBy(INTERVAL_MS * 4)

				// The raw count, not a rate: integer division passed for anything from four to
				// seven samples, so a second loop that only partly overlapped went unnoticed.
				assertThat(samples - before).isEqualTo(4)
			} finally {
				watcher.close()
			}
		}

	@Test
	fun `a listener that throws does not kill sampling for the rest of the session`() =
		runTest {
			val dispatcher = StandardTestDispatcher(testScheduler)
			var samples = 0
			val watcher = watcher(dispatcher) { samples++ }

			try {
				var thrown = 0
				watcher.listener =
					NetworkUsageWatcher.NetworkUsageListener {
						if (thrown++ == 0) {
							throw IllegalStateException("listener blew up")
						}
					}

				watcher.startWatching()
				advanceTimeBy(INTERVAL_MS * 4)

				// Uncaught, the exception ends the coroutine while isWatching stays true, so every
				// later startWatching() is refused and the charts freeze for good.
				assertThat(samples).isGreaterThan(1)
				assertThat(watcher.isWatching).isTrue()
			} finally {
				watcher.close()
			}
		}

	private companion object {
		const val INTERVAL_MS = 1_000L
		const val TEST_UID = 10_123
	}
}
