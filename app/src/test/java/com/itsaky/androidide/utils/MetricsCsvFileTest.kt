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
import kotlin.random.Random

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

	/**
	 * A session whose columns move the way a device's do, rather than climbing by one per row.
	 *
	 * Seeded, so the sizes above are the same on every run and in CI.
	 */
	private fun noisySnapshot(rows: Int): MetricsCsv.Snapshot {
		val random = Random(20260907L)
		val times = LongArray(rows) { AT + it * 1_000L + random.nextInt(80) }

		fun series(next: () -> Long) = MetricsCsv.Series(times, LongArray(rows) { next() })
		var ide = 600_000_000L
		var daemon = 780_000_000L
		var celsius = 32_000L
		return MetricsCsv.Snapshot(
			rowTimes = times,
			memory =
				mapOf(
					"IDE" to series { (ide + random.nextInt(-6_000_000, 6_000_000)).also { ide = it } },
					"Gradle Daemon" to series { (daemon + random.nextInt(-40_000_000, 40_000_000)).also { daemon = it } },
				),
			// Bursty: mostly idle, occasionally a download.
			networkReceived = series { if (random.nextInt(6) == 0) random.nextLong(2_000_000) else random.nextLong(4_000) },
			networkTransmitted = series { if (random.nextInt(8) == 0) random.nextLong(300_000) else random.nextLong(1_500) },
			temperature = series { (celsius + random.nextInt(-300, 300)).also { celsius = it } },
			power = series { 1_200_000L + random.nextLong(3_500_000) },
			thermal = series { if (random.nextInt(10) == 0) random.nextLong(4) else 0L },
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
	fun `compressing is worth doing on a session that is not a straight line`() {
		// Against noise, not against [snapshot]'s ramp. A file whose every column advances by a
		// constant compresses about fifty-fold, so a bound met by that says nothing about a real
		// session -- and this test exists to notice if the extra step ever stops earning its place.
		// Every column here moves the way its metric does on a device: memory in steps of megabytes,
		// network in bursts, temperature and power drifting, thermal status flipping.
		//
		// This session is deliberately noisier than a real one -- uniformly random power draw and
		// network bursts, where a device gives smooth drifts -- so what it achieves is a floor, not
		// an estimate: 40896 -> 14722 bytes, 2.8x, against 4.6x measured on a real 86-row
		// attachment on a Pixel 6 Pro. Halving is the bound, which compression bypassed fails and
		// a shift in gzip's tuning does not.
		val plain = MetricsCsvFile.write(context, noisySnapshot(500), AT, zone)!!.length()
		val compressed = MetricsCsvFile.writeForReport(context, noisySnapshot(500), AT, zone)!!.length()

		assertThat(compressed).isLessThan(plain / 2)
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
		val file: File = MetricsCsvFile.writeForReport(context, snapshot(2), AT, zone)!!

		assertThat(file.absolutePath).startsWith(context.cacheDir.absolutePath)
	}

	private companion object {
		/** 2026-09-06T22:33:40.123 local. */
		const val AT = 1_788_759_220_123L
	}
}
