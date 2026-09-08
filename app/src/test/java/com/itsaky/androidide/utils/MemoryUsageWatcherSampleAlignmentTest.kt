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
 * That a row of the exported metrics file is one moment (ADFA-5531).
 *
 * The exported file states a time per row, and every value on that row has to be the one recorded
 * at it. The sampler runs on its own thread and the export reads from the UI thread, so the only
 * thing making that true is where the sampler's appends happen and how many calls the reader makes.
 */
@RunWith(RobolectricTestRunner::class)
class MemoryUsageWatcherSampleAlignmentTest {
	private var clock = 1_700_000_000_000L

	private fun watcher(readPssKb: (Int, android.os.Debug.MemoryInfo) -> Int = { _, _ -> PSS_KB }) =
		MemoryUsageWatcher(
			nowMillis = {
				clock += TICK_MILLIS
				clock
			},
			// The seam is a factory now (ADFA-5574): which read is correct depends on the process.
			// These cases are about when values are appended, not how they are obtained, so every
			// process gets the same stub.
			readerFor = { ProcessMemoryReader { pid, scratch -> readPssKb(pid, scratch) } },
		)

	@Test
	fun `a read taken during a sample sees times and values that agree`() {
		lateinit var watcher: MemoryUsageWatcher
		var midSample: MemoryUsageWatcher.MemoryHistory? = null

		// Read from inside the sample, which is the interleaving the sampling thread and the UI
		// thread can produce for real. Appending the time and the values in separate critical
		// sections left this window: the reader caught a buffer with one more timestamp in it than
		// values, so every value in the exported file sat on the row below its own timestamp.
		watcher =
			watcher { _, _ ->
				if (midSample == null) {
					midSample = watcher.history()
				}
				PSS_KB
			}
		watcher.watchProcess(PID, "IDE")

		watcher.readUsages()
		watcher.readUsages()

		val history = checkNotNull(midSample)
		val stamped = history.times.count { it != MetricsCsv.NO_SAMPLE }
		val measured =
			history.processes
				.single()
				.usage
				.count { it != 0L }
		assertThat(measured).isEqualTo(stamped)
	}

	@Test
	fun `a completed sample stamps every process with the same time`() {
		val watcher = watcher()
		watcher.watchProcess(PID, "IDE")
		watcher.watchProcess(OTHER_PID, "Gradle Daemon")

		watcher.readUsages()

		// The two processes are read one after the other, but they belong to one row, so they share
		// its time -- and each has exactly one value against it.
		val history = watcher.history()
		assertThat(history.times.count { it != MetricsCsv.NO_SAMPLE }).isEqualTo(1)
		history.processes.forEach { process ->
			assertThat(process.usage.count { it != 0L }).isEqualTo(1)
		}
	}

	@Test
	fun `the times come back with the values, not from a call of their own`() {
		val watcher = watcher()
		watcher.watchProcess(PID, "IDE")
		watcher.readUsages()

		// The guard on the fix above: one accessor, so a caller cannot reintroduce the window by
		// asking for the halves separately. There is deliberately no times-only accessor.
		val history = watcher.history()
		assertThat(history.times).hasLength(MemoryUsageWatcher.MAX_USAGE_ENTRIES)
		assertThat(history.processes.single().usage).hasLength(MemoryUsageWatcher.MAX_USAGE_ENTRIES)
		assertThat(history.times.last()).isNotEqualTo(MetricsCsv.NO_SAMPLE)
		assertThat(
			history.processes
				.single()
				.usage
				.last(),
		).isEqualTo(PSS_KB * 1024L)
	}

	@Test
	fun `a copied process still says when it started being watched`() {
		val watcher = watcher()
		watcher.watchProcess(PID, "Gradle Daemon")
		watcher.readUsages()

		// getMemoryUsages hands out copies, and the copy used to drop watchedSinceMillis -- which
		// defaults to 0, i.e. "watched since the epoch". The export's guard for a process's
		// zero-filled past then never fired, so the daemon's buffer from before the daemon existed
		// came out as measured zeros rather than empty cells (ADFA-5531).
		val copied = watcher.getMemoryUsages().single()
		assertThat(copied.watchedSinceMillis).isNotEqualTo(0L)
		assertThat(copied.watchedSinceMillis).isEqualTo(watcher.getMemoryUsage(PID)!!.watchedSinceMillis)
	}

	private companion object {
		const val PID = 4242

		const val OTHER_PID = 4243

		/** Any non-zero reading; the test counts measured samples rather than reading values. */
		const val PSS_KB = 512

		/** Enough that no two sample times collide. */
		const val TICK_MILLIS = 1_000L
	}
}
