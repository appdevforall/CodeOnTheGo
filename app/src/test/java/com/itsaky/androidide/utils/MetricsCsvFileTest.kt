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

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.ZoneId

/**
 * The file the export button writes (ADFA-5531).
 *
 * [MetricsCsvFile.KEEP_RECENT] and the clock were made injectable for a test and then had none, so
 * the bound on the directory and the name the file is given were both unasserted.
 */
@RunWith(RobolectricTestRunner::class)
class MetricsCsvFileTest {
	private val context = ApplicationProvider.getApplicationContext<Context>()

	/**
	 * Fixed, not the machine's own.
	 *
	 * The name asserted below is a rendering of [AT] in a particular zone, so leaving the zone to
	 * the default made this pass here and fail wherever CI happens to be.
	 */
	private val zone: ZoneId = ZoneId.of("America/Los_Angeles")

	private fun snapshot(rows: Int): MetricsCsv.Snapshot {
		val times = LongArray(rows) { AT + it * 1_000L }
		return MetricsCsv.Snapshot(
			rowTimes = times,
			sampleIntervalMillis = INTERVAL_MS,
			memory = mapOf(MetricsCsv.PROC_IDE to MetricsCsv.Series(times, LongArray(rows) { 600_000_000L + it })),
		)
	}

	@Test
	fun `an export is plain csv the user can open`() {
		val file = MetricsCsvFile.write(context, snapshot(3), AT, zone)!!

		assertThat(file.name).isEqualTo("2026_09_06_22_33_40_123.csv")
		assertThat(file.readText().lineSequence().first()).startsWith("\"timestamp\"")
	}

	@Test
	fun `the export directory stays bounded`() {
		repeat(MetricsCsvFile.KEEP_RECENT + 4) { i ->
			MetricsCsvFile.write(context, snapshot(1), AT + i * 1_000L, zone)
		}

		val directory = MetricsCsvFile.write(context, snapshot(1), AT + 90_000L, zone)!!.parentFile!!

		assertThat(directory.listFiles()!!.size).isAtMost(MetricsCsvFile.KEEP_RECENT)
	}

	@Test
	fun `the limit holds when the file just written is not the newest on disk`() {
		// Pruning used to pick "the oldest n" across every file and then skip the one just written,
		// which deleted one too few whenever that one sorted into the set -- and the directory crept
		// one over the limit each time. Two writes inside a single filesystem timestamp are enough
		// to sort it there.
		//
		// Dating the existing files into the future is what puts the new one at the front of the
		// sort deterministically. Tying them all to one *past* value does not: the file written last
		// still carries a real mtime, so it sorts last, is never in the set, and the skip never
		// fires -- which is how the first version of this test passed against the unfixed code.
		val future = System.currentTimeMillis() + 1_000_000L
		repeat(MetricsCsvFile.KEEP_RECENT + 3) { i ->
			MetricsCsvFile.write(context, snapshot(1), AT + i, zone)!!.setLastModified(future)
		}

		val directory = MetricsCsvFile.write(context, snapshot(1), AT + 900L, zone)!!.parentFile!!

		assertThat(directory.listFiles()!!.size).isAtMost(MetricsCsvFile.KEEP_RECENT)
	}

	@Test
	fun `files land under the cache, which the platform may reclaim`() {
		val file: File = MetricsCsvFile.write(context, snapshot(2), AT, zone)!!

		assertThat(file.absolutePath).startsWith(context.cacheDir.absolutePath)
	}

	private companion object {
		/** 2026-09-06T22:33:40.123 local. */
		const val AT = 1_788_759_220_123L

		/** The gap between the rows these fixtures build. */
		const val INTERVAL_MS = 1_000L
	}
}
