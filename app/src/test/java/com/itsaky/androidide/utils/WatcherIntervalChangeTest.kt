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
import org.junit.Test

/**
 * Pins that changing the sampling rate discards the history (ADFA-5486).
 *
 * The chart reads a sample's age from its position, which assumes every sample is the same age
 * apart. A buffer holding samples taken at two rates would silently misdate all the older ones, so
 * the history goes when the rate does.
 */
class WatcherIntervalChangeTest {
	private fun networkWatcher(readings: List<Long>): Pair<NetworkUsageWatcher, () -> Unit> {
		var index = -1
		val watcher =
			NetworkUsageWatcher(
				uid = TEST_UID,
				readRxBytes = { readings[index.coerceIn(0, readings.lastIndex)] },
				readTxBytes = { readings[index.coerceIn(0, readings.lastIndex)] },
			)
		return watcher to {
			index++
			watcher.sampleOnce()
		}
	}

	@Test
	fun `changing the network interval discards the samples`() {
		val (watcher, sample) = networkWatcher(listOf(0L, 1_000L, 3_000L))
		repeat(3) { sample() }
		assertThat(watcher.getUsage().received.sum()).isGreaterThan(0L)

		watcher.updateInterval = 5_000L

		assertThat(watcher.getUsage().received.sum()).isEqualTo(0L)
		assertThat(watcher.getUsage().transmitted.sum()).isEqualTo(0L)
	}

	@Test
	fun `setting the same network interval keeps the samples`() {
		val (watcher, sample) = networkWatcher(listOf(0L, 1_000L))
		repeat(2) { sample() }
		val before = watcher.getUsage().received.sum()

		watcher.updateInterval = watcher.updateInterval

		assertThat(watcher.getUsage().received.sum()).isEqualTo(before)
	}

	@Test
	fun `the cumulative baseline is dropped too`() {
		// Otherwise the first sample after the change would report every byte since the last one as
		// a single delta -- a spike at exactly the moment the user changed the rate.
		val (watcher, sample) = networkWatcher(listOf(0L, 1_000L, 50_000L))
		repeat(2) { sample() }

		watcher.updateInterval = 2_000L
		sample()

		assertThat(watcher.getUsage().received.sum()).isEqualTo(0L)
	}

	private companion object {
		const val TEST_UID = 10_123
	}
}
