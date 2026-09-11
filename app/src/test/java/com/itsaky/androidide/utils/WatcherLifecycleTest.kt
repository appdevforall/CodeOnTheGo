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

/**
 * Pins the sampling loop's lifecycle, from three defects found in review of ADFA-5487/5489.
 *
 * The loop used to be launched with its own `SupervisorJob`, which meant the watcher's scope could
 * not cancel it: it ran until it next observed the `watching` flag, and it spends nearly all its
 * time asleep in `delay(updateInterval)` -- up to a minute at the slowest rate now that the rate is
 * configurable. And an exception anywhere in the body ended the coroutine while the flag stayed
 * set, so sampling stopped for good and every later restart was refused.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WatcherLifecycleTest {
	@Test
	fun `restarting inside the sampling interval does not leave two loops running`() =
		runTest {
			val dispatcher = StandardTestDispatcher(testScheduler)
			var samples = 0
			val watcher =
				MemoryUsageWatcher(
					updateInterval = 1_000L,
					coroutineDispatcher = dispatcher,
					mainDispatcher = dispatcher,
				)
			watcher.listener = MemoryUsageWatcher.MemoryUsageListener { samples++ }

			watcher.startWatching()
			advanceTimeBy(1_500L)
			val afterFirstRun = samples

			// Stop and start again while the loop is asleep mid-interval. The old loop used to wake
			// up, see the flag set again, and carry on beside the new one.
			watcher.stopWatching(unwatchAll = false)
			watcher.startWatching()
			advanceTimeBy(3_000L)

			// Three more intervals, one sampler: three more samples, not six.
			val duringSecondRun = samples - afterFirstRun
			assertThat(duringSecondRun).isAtMost(4)

			watcher.close()
		}

	@Test
	fun `stopping actually stops sampling`() =
		runTest {
			val dispatcher = StandardTestDispatcher(testScheduler)
			var samples = 0
			val watcher =
				MemoryUsageWatcher(
					updateInterval = 100L,
					coroutineDispatcher = dispatcher,
					mainDispatcher = dispatcher,
				)
			watcher.listener = MemoryUsageWatcher.MemoryUsageListener { samples++ }

			watcher.startWatching()
			advanceTimeBy(500L)
			watcher.stopWatching(unwatchAll = false)
			val atStop = samples

			advanceTimeBy(2_000L)

			assertThat(samples).isEqualTo(atStop)
			assertThat(watcher.isWatching).isFalse()
		}

	@Test
	fun `a listener that throws does not kill sampling`() =
		runTest {
			val dispatcher = StandardTestDispatcher(testScheduler)
			var notifications = 0
			val watcher =
				MemoryUsageWatcher(
					updateInterval = 100L,
					coroutineDispatcher = dispatcher,
					mainDispatcher = dispatcher,
				)
			watcher.listener =
				MemoryUsageWatcher.MemoryUsageListener {
					notifications++
					throw IllegalStateException("listener blew up")
				}

			watcher.startWatching()
			advanceTimeBy(1_000L)

			// The loop used to die on the first throw, leaving isWatching true so nothing could
			// restart it. It should keep sampling instead.
			assertThat(notifications).isAtLeast(5)
			assertThat(watcher.isWatching).isTrue()

			// runTest drains the scheduler when the test ends, which an unstopped loop never lets
			// it do.
			watcher.close()
		}

	@Test
	fun `a watcher can be restarted after a listener throws`() =
		runTest {
			val dispatcher = StandardTestDispatcher(testScheduler)
			var notifications = 0
			val watcher =
				MemoryUsageWatcher(
					updateInterval = 100L,
					coroutineDispatcher = dispatcher,
					mainDispatcher = dispatcher,
				)
			watcher.listener =
				MemoryUsageWatcher.MemoryUsageListener {
					notifications++
					throw IllegalStateException("listener blew up")
				}

			watcher.startWatching()
			advanceTimeBy(300L)
			watcher.stopWatching(unwatchAll = false)

			watcher.listener = MemoryUsageWatcher.MemoryUsageListener { notifications++ }
			watcher.startWatching()
			val beforeRestart = notifications
			advanceTimeBy(500L)

			assertThat(watcher.isWatching).isTrue()
			assertThat(notifications).isGreaterThan(beforeRestart)

			watcher.close()
		}

	@Test
	fun `close stops sampling and refuses to restart`() =
		runTest {
			val dispatcher = StandardTestDispatcher(testScheduler)
			var samples = 0
			val watcher =
				MemoryUsageWatcher(
					updateInterval = 100L,
					coroutineDispatcher = dispatcher,
					mainDispatcher = dispatcher,
				)
			watcher.listener = MemoryUsageWatcher.MemoryUsageListener { samples++ }

			watcher.startWatching()
			advanceTimeBy(300L)
			watcher.close()
			val atClose = samples

			// The scope is cancelled, so a restart launches nothing.
			watcher.startWatching()
			advanceTimeBy(1_000L)

			assertThat(samples).isEqualTo(atClose)
		}
}
