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
import org.junit.runners.JUnit4
import java.time.ZoneId

/**
 * The canonical metrics file format (ADFA-5531).
 *
 * Pinned closely because it is not this ticket's file alone: ADFA-5494 reads it back to restore the
 * chart history, and ADFA-5526 and ADFA-5534 attach copies to crash reports and to feedback. A
 * column that quietly changes shape breaks a consumer that is not in front of you.
 */
@RunWith(JUnit4::class)
class MetricsCsvTest {
	private val zone: ZoneId = ZoneId.of("America/Los_Angeles")

	private fun render(snapshot: MetricsCsv.Snapshot): List<String> =
		StringBuilder()
			.also { MetricsCsv.write(snapshot, zone, it) }
			.toString()
			.trimEnd('\n')
			.split('\n')

	private fun snapshot(
		rowTimes: LongArray = longArrayOf(T0, T0 + 1_000L),
		memory: Map<String, MetricsCsv.Series> = emptyMap(),
		networkReceived: MetricsCsv.Series = MetricsCsv.Series.EMPTY,
		networkTransmitted: MetricsCsv.Series = MetricsCsv.Series.EMPTY,
		temperature: MetricsCsv.Series = MetricsCsv.Series.EMPTY,
		power: MetricsCsv.Series = MetricsCsv.Series.EMPTY,
		thermal: MetricsCsv.Series = MetricsCsv.Series.EMPTY,
		annotations: List<MetricsCsv.Marker> = emptyList(),
	) = MetricsCsv.Snapshot(
		rowTimes = rowTimes,
		memory = memory,
		networkReceived = networkReceived,
		networkTransmitted = networkTransmitted,
		temperature = temperature,
		power = power,
		thermal = thermal,
		annotations = annotations,
	)

	private fun series(
		times: LongArray,
		values: LongArray,
		since: Long = 0L,
	) = MetricsCsv.Series(times, values, since)

	@Test
	fun `an export with nothing sampled is a header and no rows`() {
		// The empty case is not exotic: changing the sampling rate clears every buffer, so the very
		// next export has nothing to say. It still produces a file.
		val lines = render(snapshot(rowTimes = LongArray(4)))

		assertThat(lines).hasSize(1)
		assertThat(lines.single()).isEqualTo(EXPECTED_HEADER)
	}

	@Test
	fun `a row's time is the one recorded for that sample`() {
		val lines = render(snapshot(rowTimes = longArrayOf(T0)))

		// Read back, not reconstructed from an index and an interval: 2026-09-06T22:33:40.123 in
		// Los Angeles, with the offset that says which 22:33 it was.
		assertThat(lines[1]).startsWith("\"2026-09-06T22:33:40.123-07:00\"")
	}

	@Test
	fun `the fraction is always three digits, even when it ends in zero`() {
		// ISO_OFFSET_DATE_TIME drops trailing zeros and would write ".38" here, giving a column of
		// varying width. Both parse; only one lines up with the milliseconds the filename carries.
		val lines = render(snapshot(rowTimes = longArrayOf(T0 - 43L)))

		assertThat(lines[1]).startsWith("\"2026-09-06T22:33:40.080-07:00\"")
	}

	@Test
	fun `an index nothing was sampled at is not a row`() {
		// The buffers are fixed-length and start full of zeros, so most of a young session's buffer
		// has never been written. Those are absent rows, not rows of zeros.
		val lines = render(snapshot(rowTimes = longArrayOf(0L, 0L, T0, 0L, T0 + 1_000L)))

		assertThat(lines).hasSize(3)
		assertThat(lines[1]).contains("22:33:40.123")
		assertThat(lines[2]).contains("22:33:41.123")
	}

	@Test
	fun `every row has as many cells as the header`() {
		val times = longArrayOf(T0, T0 + 1_000L)
		val lines =
			render(
				snapshot(
					rowTimes = times,
					memory = mapOf("IDE" to series(times, longArrayOf(1L, 2L))),
					networkReceived = series(times, longArrayOf(3L, 4L)),
					annotations = listOf(MetricsCsv.Marker(T0, "assemble", "TASK")),
				),
			)

		lines.forEach { line ->
			assertThat(cellsIn(line)).hasSize(MetricsCsv.HEADER.size)
		}
	}

	@Test
	fun `a process that was not being watched yet leaves the cell empty, not zero`() {
		val times = longArrayOf(T0, T0 + 1_000L)
		val lines =
			render(
				snapshot(
					rowTimes = times,
					// The Gradle daemon appears when a build starts, and its buffer is zero-filled
					// back to the beginning of the session (ADFA-5514). Reporting those zeros as
					// measurements would say the daemon was running and using nothing.
					memory = mapOf("Gradle Daemon" to series(times, longArrayOf(0L, 900L), since = T0 + 1_000L)),
				),
			)

		val daemon = MetricsCsv.HEADER.indexOf("gradle_daemon_pss_bytes")
		assertThat(cellsIn(lines[1])[daemon]).isEmpty()
		assertThat(cellsIn(lines[2])[daemon]).isEqualTo("900")
	}

	@Test
	fun `a series that never recorded leaves its columns empty`() {
		val times = longArrayOf(T0, T0 + 1_000L)
		val lines =
			render(
				snapshot(
					rowTimes = times,
					memory = mapOf("IDE" to series(times, longArrayOf(5L, 6L))),
					// A device whose traffic counters are unsupported never records a sample, and
					// zero bytes transferred is a different statement from no measurement.
					networkReceived = MetricsCsv.Series.EMPTY,
				),
			)

		val rx = MetricsCsv.HEADER.indexOf("net_rx_bytes")
		assertThat(cellsIn(lines[1])[rx]).isEmpty()
	}

	@Test
	fun `strings are quoted, numbers are not, and a quote inside one is doubled`() {
		val times = longArrayOf(T0)
		val lines =
			render(
				snapshot(
					rowTimes = times,
					memory = mapOf("IDE" to series(times, longArrayOf(7L))),
					annotations = listOf(MetricsCsv.Marker(T0, ":app:say \"hi\"", "TASK")),
				),
			)

		val cells = cellsIn(lines[1])
		assertThat(cells[MetricsCsv.HEADER.indexOf("ide_pss_bytes")]).isEqualTo("7")
		assertThat(cells[MetricsCsv.HEADER.indexOf("annotation")]).isEqualTo("\":app:say \"\"hi\"\"\"")
		assertThat(cells[MetricsCsv.HEADER.indexOf("annotation_kind")]).isEqualTo("\"TASK\"")
	}

	@Test
	fun `an annotation lands on the sample nearest in time, not only an exact match`() {
		val times = longArrayOf(T0, T0 + 1_000L, T0 + 2_000L)
		val lines =
			render(
				snapshot(
					rowTimes = times,
					// Recorded when the build started, which is between two samples. Requiring an
					// exact match would drop nearly every marker in the file.
					annotations = listOf(MetricsCsv.Marker(T0 + 1_600L, "Build started", "BUILD_STARTED")),
				),
			)

		val column = MetricsCsv.HEADER.indexOf("annotation")
		assertThat(cellsIn(lines[1])[column]).isEmpty()
		assertThat(cellsIn(lines[2])[column]).isEmpty()
		assertThat(cellsIn(lines[3])[column]).isEqualTo("\"Build started\"")
	}

	@Test
	fun `two annotations falling on one sample keep the earlier one`() {
		val times = longArrayOf(T0)
		val lines =
			render(
				snapshot(
					rowTimes = times,
					annotations =
						listOf(
							MetricsCsv.Marker(T0 + 40L, "second", "TASK"),
							MetricsCsv.Marker(T0 + 10L, "first", "TASK"),
						),
				),
			)

		// One annotation column per row by definition, so the loser is dropped rather than
		// overwriting the winner or being appended into the same cell.
		assertThat(cellsIn(lines[1])[MetricsCsv.HEADER.indexOf("annotation")]).isEqualTo("\"first\"")
	}

	@Test
	fun `an annotation recorded on the monotonic clock lands on the right row`() {
		// The store stamps annotations with elapsedRealtime and the samples carry epoch millis.
		// Recorded three seconds ago, on a device up for two hours.
		val upFor = 2 * 60 * 60 * 1000L
		val recordedAt = upFor - 3_000L
		val times = longArrayOf(T0, T0 + 1_000L, T0 + 2_000L, T0 + 3_000L)
		val onEpoch = MetricsCsv.epochFor(recordedAt, nowEpochMillis = T0 + 3_000L, nowMonotonicMillis = upFor)

		val lines =
			render(
				snapshot(
					rowTimes = times,
					annotations = listOf(MetricsCsv.Marker(onEpoch, "Build started", "BUILD_STARTED")),
				),
			)

		val column = MetricsCsv.HEADER.indexOf("annotation")
		assertThat(cellsIn(lines[1])[column]).isEqualTo("\"Build started\"")
	}

	@Test
	fun `an unconverted monotonic time would land on the oldest row`() {
		// What the mix-up looked like on a device: a monotonic time is a few hours and an epoch time
		// is decades, so every row is about equally far away and the nearest-row search picks
		// whichever number is smallest -- the oldest sample, whenever the event really happened.
		val times = longArrayOf(T0, T0 + 1_000L, T0 + 2_000L)
		val lines =
			render(
				snapshot(
					rowTimes = times,
					annotations = listOf(MetricsCsv.Marker(2 * 60 * 60 * 1000L, "Build started", "BUILD_STARTED")),
				),
			)

		val column = MetricsCsv.HEADER.indexOf("annotation")
		assertThat(cellsIn(lines[1])[column]).isEqualTo("\"Build started\"")
		assertThat(cellsIn(lines[3])[column]).isEmpty()
	}

	@Test
	fun `hasRows tells a caller whether the file is worth sending`() {
		// The writer always produces a file, header included, because the export button asked for
		// one. Attaching it to feedback is a different question (ADFA-5534): an empty attachment on
		// a report from a freshly started IDE is worse than no attachment.
		assertThat(snapshot(rowTimes = LongArray(8)).hasRows).isFalse()
		assertThat(snapshot(rowTimes = longArrayOf(0L, 0L, T0, 0L)).hasRows).isTrue()
	}

	@Test
	fun `the memory columns are the three the chart can plot`() {
		// Fixed, not derived from what is being watched: the set changes mid-session, and a header
		// that followed it would describe a different file each time.
		assertThat(MetricsCsv.MEMORY_COLUMNS).containsExactly("IDE", "Gradle Tooling", "Gradle Daemon").inOrder()
		assertThat(MetricsCsv.HEADER).containsAtLeast("ide_pss_bytes", "gradle_tooling_pss_bytes", "gradle_daemon_pss_bytes")
	}

	/** Splits a row on commas that are not inside a quoted cell. */
	private fun cellsIn(line: String): List<String> {
		val cells = mutableListOf<String>()
		val cell = StringBuilder()
		var quoted = false
		line.forEach { c ->
			when {
				c == '"' -> {
					quoted = !quoted
					cell.append(c)
				}

				c == ',' && !quoted -> {
					cells += cell.toString()
					cell.setLength(0)
				}

				else -> {
					cell.append(c)
				}
			}
		}
		cells += cell.toString()
		return cells
	}

	private companion object {
		/**
		 * The header line, spelled out rather than derived from [MetricsCsv.HEADER].
		 *
		 * This is the file's contract, and a test that builds its expectation from the same constant
		 * the code builds the file from asserts only that the code is self-consistent -- a renamed
		 * column or a change in how cells are quoted would rename it here too and stay green.
		 * ADFA-5494 reads this format back, and ADFA-5526 and ADFA-5534 ship it inside reports, so a
		 * schema change should have to come and edit this line on purpose.
		 */
		const val EXPECTED_HEADER =
			"\"timestamp\",\"ide_pss_bytes\",\"gradle_tooling_pss_bytes\",\"gradle_daemon_pss_bytes\",\"net_rx_bytes\",\"net_tx_bytes\",\"battery_temp_millicelsius\",\"power_microwatts\",\"thermal_status\",\"annotation\",\"annotation_kind\""

		/** 2026-09-06T22:33:40.123 in America/Los_Angeles, which is UTC-7 at that date. */
		const val T0 = 1_788_759_220_123L
	}
}
