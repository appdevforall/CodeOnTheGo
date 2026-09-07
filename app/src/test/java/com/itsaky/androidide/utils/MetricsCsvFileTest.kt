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
import java.util.zip.GZIPInputStream

/**
 * The two files the metrics format is written to: the user's export, and the compressed copy that
 * travels with a report (ADFA-5534).
 */
@RunWith(RobolectricTestRunner::class)
class MetricsCsvFileTest {
	private val context = ApplicationProvider.getApplicationContext<Context>()

	/**
	 * Fixed, not the machine's own.
	 *
	 * The names below are a rendering of [AT] in a particular zone, so leaving the zone to the
	 * default made them pass here and fail wherever CI happens to be.
	 */
	private val zone: ZoneId = ZoneId.of("America/Los_Angeles")

	private fun snapshot(rows: Int): MetricsCsv.Snapshot {
		val times = LongArray(rows) { AT + it * 1_000L }
		return MetricsCsv.Snapshot(
			rowTimes = times,
			memory = mapOf("IDE" to MetricsCsv.Series(times, LongArray(rows) { 600_000_000L + it })),
		)
	}

	@Test
	fun `an export is plain csv the user can open`() {
		val file = MetricsCsvFile.write(context, snapshot(3), AT, zone)!!

		assertThat(file.name).isEqualTo("2026_09_06_22_33_40_123.csv")
		assertThat(file.readText().lineSequence().first()).startsWith("\"timestamp\"")
	}

	@Test
	fun `a report copy is gzipped, and unzips to the same csv`() {
		val plain = MetricsCsvFile.write(context, snapshot(50), AT, zone)!!.readText()
		val compressed = MetricsCsvFile.writeForReport(context, snapshot(50), AT, zone)!!

		assertThat(compressed.name).isEqualTo("2026_09_06_22_33_40_123.csv.gz")
		val unzipped = GZIPInputStream(compressed.inputStream()).bufferedReader().use { it.readText() }
		assertThat(unzipped).isEqualTo(plain)
	}

	@Test
	fun `compressing is worth doing`() {
		// The rows are near-identical by nature -- the timestamp advances by a constant and the
		// magnitudes barely move -- so this travels far smaller than it reads. If that ever stops
		// being true, the compression is buying nothing and the extra step should go.
		val plain = MetricsCsvFile.write(context, snapshot(500), AT, zone)!!.length()
		val compressed = MetricsCsvFile.writeForReport(context, snapshot(500), AT, zone)!!.length()

		assertThat(compressed).isLessThan(plain / 4)
	}

	@Test
	fun `a report copy does not evict the user's exports`() {
		// They prune independently. A feedback send must not delete an export the user is part-way
		// through handing to another app.
		val export = MetricsCsvFile.write(context, snapshot(2), AT, zone)!!
		repeat(MetricsCsvFile.KEEP_RECENT + 3) { i ->
			MetricsCsvFile.writeForReport(context, snapshot(2), AT + i + 1L, zone)
		}

		assertThat(export.exists()).isTrue()
		assertThat(export.parentFile).isNotEqualTo(
			MetricsCsvFile.writeForReport(context, snapshot(2), AT + 99L, zone)!!.parentFile,
		)
	}

	@Test
	fun `both directories stay bounded`() {
		repeat(20) { i -> MetricsCsvFile.write(context, snapshot(2), AT + i.toLong(), zone) }
		repeat(20) { i -> MetricsCsvFile.writeForReport(context, snapshot(2), AT + i.toLong(), zone) }

		val exports = MetricsCsvFile.write(context, snapshot(2), AT + 500L, zone)!!.parentFile!!
		val reports = MetricsCsvFile.writeForReport(context, snapshot(2), AT + 500L, zone)!!.parentFile!!
		assertThat(exports.listFiles()!!.size).isAtMost(MetricsCsvFile.KEEP_RECENT)
		assertThat(reports.listFiles()!!.size).isAtMost(MetricsCsvFile.KEEP_RECENT)
	}

	@Test
	fun `files land under the cache, which the platform may reclaim`() {
		val file: File = MetricsCsvFile.writeForReport(context, snapshot(2), AT, zone)!!

		assertThat(file.absolutePath).startsWith(context.cacheDir.absolutePath)
	}

	private companion object {
		/** 2026-09-06T22:33:40.123 local. */
		const val AT = 1_788_759_220_123L
	}
}
