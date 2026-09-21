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
 * Pins that the sampling loop honours [MemoryUsageWatcher]'s configured interval.
 *
 * The loop used to `delay(1000)` regardless of the constructor argument, so the interval was fixed
 * at one second whatever a caller asked for -- the "sample time is fixed" of ADFA-5486, in the code
 * rather than only in the UI.
 *
 * Sampling runs on an injected test dispatcher, so these advance virtual time and never wait on a
 * real clock. No process is watched, so a sample does no work and only the interval governs the
 * rate.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MemoryUsageWatcherIntervalTest {
	@Test
	fun `the sampling rate follows the configured interval`() =
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
			advanceTimeBy(1_000L)
			watcher.stopWatching()

			// One second of virtual time at 100ms. The hardcoded one-second delay this replaced
			// would have produced one sample regardless of the interval asked for.
			assertThat(samples).isAtLeast(9)
			assertThat(samples).isAtMost(11)
		}

	@Test
	fun `a longer interval samples proportionally less often`() =
		runTest {
			val dispatcher = StandardTestDispatcher(testScheduler)
			var samples = 0

			val watcher =
				MemoryUsageWatcher(
					updateInterval = 500L,
					coroutineDispatcher = dispatcher,
					mainDispatcher = dispatcher,
				)
			watcher.listener = MemoryUsageWatcher.MemoryUsageListener { samples++ }

			watcher.startWatching()
			advanceTimeBy(1_000L)
			watcher.stopWatching()

			// Five times the interval, so a fifth of the samples. With the interval ignored this
			// was indistinguishable from the 100ms case.
			assertThat(samples).isAtLeast(1)
			assertThat(samples).isAtMost(3)
		}
}
