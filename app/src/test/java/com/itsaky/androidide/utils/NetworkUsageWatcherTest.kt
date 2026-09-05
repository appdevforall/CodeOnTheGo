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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the accounting decisions ADFA-5489 was scoped around: the platform counters are cumulative,
 * so what is plotted is the delta between samples, and a counter reset must not plot as negative
 * traffic.
 *
 * These drive [NetworkUsageWatcher.sampleOnce] directly rather than starting the sampling loop, so
 * there is no waiting and no dependence on thread timing.
 */
@RunWith(RobolectricTestRunner::class)
class NetworkUsageWatcherTest {
	/**
	 * A watcher fed a scripted sequence of cumulative readings, advancing one step per sample.
	 */
	private class Fixture(
		rx: List<Long>,
		tx: List<Long> = rx,
	) {
		private var index = -1
		private val rxReadings = rx
		private val txReadings = tx

		val watcher =
			NetworkUsageWatcher(
				uid = TEST_UID,
				readRxBytes = { rxReadings[index.coerceIn(0, rxReadings.lastIndex)] },
				readTxBytes = { txReadings[index.coerceIn(0, txReadings.lastIndex)] },
			)

		/** Takes [count] samples, walking the scripted readings. */
		fun sample(count: Int) {
			repeat(count) {
				index++
				watcher.sampleOnce()
			}
		}
	}

	/** The last [count] recorded samples, ignoring the leading zeros of an unfilled buffer. */
	private fun LongArray.recent(count: Int): List<Long> = takeLast(count)

	@Test
	fun `history is all zeros before the first sample`() {
		val fixture = Fixture(listOf(5_000L))

		val usage = fixture.watcher.getUsage()

		assertThat(usage.received).hasLength(NetworkUsageWatcher.MAX_USAGE_ENTRIES)
		assertThat(usage.received.sum()).isEqualTo(0L)
		assertThat(usage.transmitted.sum()).isEqualTo(0L)
	}

	@Test
	fun `plots deltas between samples, not the cumulative counters`() {
		// Cumulative since boot: 1000, then +500, then +2500.
		val fixture = Fixture(listOf(1_000L, 1_500L, 4_000L))

		fixture.sample(3)
		val usage = fixture.watcher.getUsage()

		// The first sample only establishes a baseline, so it contributes 0 rather than a
		// 1000-byte spike for traffic that happened before the chart existed.
		assertThat(usage.received.recent(3)).containsExactly(0L, 500L, 2_500L).inOrder()
		assertThat(usage.transmitted.recent(3)).containsExactly(0L, 500L, 2_500L).inOrder()
	}

	@Test
	fun `a counter reset records zero rather than negative traffic`() {
		// A reboot or re-based accounting makes the counter go backwards.
		val fixture = Fixture(listOf(10_000L, 10_400L, 200L, 700L))

		fixture.sample(4)
		val usage = fixture.watcher.getUsage()

		assertThat(usage.received.recent(4)).containsExactly(0L, 400L, 0L, 500L).inOrder()
		assertThat(usage.received.none { it < 0L }).isTrue()
	}

	@Test
	fun `received and transmitted are accounted separately`() {
		val fixture =
			Fixture(
				rx = listOf(0L, 1_000L),
				tx = listOf(0L, 7L),
			)

		fixture.sample(2)
		val usage = fixture.watcher.getUsage()

		assertThat(usage.received.recent(2)).containsExactly(0L, 1_000L).inOrder()
		assertThat(usage.transmitted.recent(2)).containsExactly(0L, 7L).inOrder()
	}

	@Test
	fun `the ring buffer keeps only the most recent samples`() {
		val capacity = NetworkUsageWatcher.MAX_USAGE_ENTRIES
		// Cumulative readings rising by 10 bytes each sample, for one more sample than fits.
		val readings = List(capacity + 2) { it * 10L }
		val fixture = Fixture(readings)

		fixture.sample(readings.size)
		val usage = fixture.watcher.getUsage()

		assertThat(usage.received).hasLength(capacity)
		// The baseline zero has been pushed out; every retained sample is a full 10-byte delta.
		assertThat(usage.received.toList()).containsNoneIn(listOf(-10L))
		assertThat(usage.received.last()).isEqualTo(10L)
		assertThat(usage.received.sum()).isEqualTo(10L * capacity)
	}

	@Test
	fun `an unsupported counter is detected and nothing is recorded`() {
		// TrafficStats.UNSUPPORTED is -1.
		val fixture = Fixture(listOf(-1L))

		fixture.sample(2)
		val usage = fixture.watcher.getUsage()

		assertThat(fixture.watcher.isSupported).isFalse()
		// In particular, -1 is not plotted as traffic.
		assertThat(usage.received.sum()).isEqualTo(0L)
		assertThat(usage.transmitted.sum()).isEqualTo(0L)
	}

	@Test
	fun `getUsage returns a copy, not the live buffer`() {
		val fixture = Fixture(listOf(0L, 100L, 300L))

		fixture.sample(2)
		val first = fixture.watcher.getUsage()
		val asHandedOut = first.received.copyOf()
		fixture.sample(1)

		// The array handed out earlier must not have been mutated by the later sample.
		assertThat(first.received).isEqualTo(asHandedOut)
		assertThat(fixture.watcher.getUsage().received).isNotEqualTo(asHandedOut)
	}

	private companion object {
		const val TEST_UID = 10_123
	}
}
