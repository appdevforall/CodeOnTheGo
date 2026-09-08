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

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/**
 * The canonical metrics file: everything the carousel has sampled, as CSV (ADFA-5531).
 *
 * One definition, because the file has several producers and consumers -- the export button here,
 * ADFA-5494's restore across process death, and the copies ADFA-5526 and ADFA-5534 attach to crash
 * reports and to feedback. Those differ from this only in compressing it.
 *
 * A row is a sampling tick, and its columns come from three watchers that each keep their own ring
 * buffer and their own coroutine. They are started together and share one interval, and every one
 * of them is cleared when that interval changes. The row's stated time is the memory watcher's,
 * recorded when it sampled, and nothing here reconstructs a time from an index.
 *
 * The other series are paired to that row by array index rather than by time, which is not the same
 * thing. Each watcher runs its own loop and does its own per-tick work, so their ticks drift apart,
 * and because the buffers are filled oldest-first the drift accumulates backwards: the further back
 * a row is, the further its network and power values can sit from its stated time. Every column is
 * a real reading with a real time behind it, so nothing in the file is invented -- but a consumer
 * must not read one row as three simultaneous measurements. Merging the series on time instead is
 * the subject of its own change; this comment says what is true until then.
 *
 * Formatting only, with no Android types, so the whole format can be tested without a device.
 */
object MetricsCsv {
	/** Media type for the written file, for the sharing intent. */
	const val MIME_TYPE = "text/csv"

	/**
	 * A sample time of zero means no sample was taken at that index: the ring buffers are
	 * fixed-length and start, and are cleared, full of zeros.
	 */
	const val NO_SAMPLE = 0L

	/**
	 * A row's time, ISO 8601 with the offset the device was on.
	 *
	 * Deliberately not the filename's format, which is built from what a filesystem allows and what
	 * sorts lexicographically. This one has to round-trip exactly for ADFA-5494 and be read by a
	 * person triaging a report from another timezone, which is what the offset is for.
	 *
	 * Spelled out rather than [DateTimeFormatter.ISO_OFFSET_DATE_TIME], which drops trailing zeros
	 * from the fraction and so writes a column of varying width -- ".38" for one row and ".123" for
	 * the next. Both parse, but a fixed three digits matches the millisecond the value is recorded
	 * at and the three the filename carries.
	 */
	private val TIMESTAMP_FORMAT: DateTimeFormatter =
		DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT)

	/**
	 * The memory series, in column order.
	 *
	 * Fixed rather than taken from whatever is being watched at export time. The set changes during
	 * a session -- the Gradle daemon appears when a build starts and goes when it exits (ADFA-5514)
	 * -- and a header that depended on it would describe a different file each time. A process that
	 * is not being watched leaves its column empty.
	 */
	val MEMORY_COLUMNS = listOf("IDE", "Gradle Tooling", "Gradle Daemon")

	@JvmStatic
	val HEADER: List<String> =
		listOf("timestamp") +
			MEMORY_COLUMNS.map { "${it.lowercase().replace(' ', '_')}_pss_bytes" } +
			listOf(
				"net_rx_bytes",
				"net_tx_bytes",
				"battery_temp_millicelsius",
				"power_microwatts",
				"thermal_status",
				"annotation",
				"annotation_kind",
			)

	/**
	 * One series of samples and the times they were recorded at.
	 *
	 * @property times When each value was sampled, oldest first and parallel to [values]. A
	 * [NO_SAMPLE] entry marks an index nothing was ever recorded at, which is what tells an empty
	 * cell apart from a measured zero.
	 * @property values The samples themselves.
	 * @property absent The in-band value this series uses for "the device did not provide a
	 * reading", or `null` if it has none. Written as an empty cell, the same as an unsampled index.
	 * A watcher that stores a sentinel -- `PowerUsageWatcher.UNAVAILABLE` is [Long.MIN_VALUE] --
	 * would otherwise put `-9223372036854775808` in a numeric column, and every consumer that
	 * averages or plots that column gets an answer that is not merely wrong but spectacular. The
	 * sentinel is named by the caller rather than known here, because this file deliberately has no
	 * Android types in it.
	 * @property since When this series started being recorded. Samples timed before it belong to
	 * the buffer's zero-filled past rather than to this series -- the Gradle daemon's buffer reaches
	 * back to the start of the session however late in it the daemon appeared.
	 */
	class Series(
		private val times: LongArray,
		private val values: LongArray,
		private val since: Long = 0L,
		private val absent: Long? = null,
	) {
		/** The value at index [i], or `null` if this series has nothing to say there. */
		fun at(i: Int): Long? {
			if (i < 0 || i >= times.size || i >= values.size) {
				return null
			}
			val time = times[i]
			if (time == NO_SAMPLE || time < since) {
				return null
			}
			val value = values[i]
			return if (value == absent) null else value
		}

		companion object {
			val EMPTY = Series(LongArray(0), LongArray(0))
		}
	}

	/**
	 * @property atMillis When the event happened.
	 * @property label Its text, already resolved.
	 * @property kind The sort of event, as the annotation store names it.
	 */
	data class Marker(
		val atMillis: Long,
		val label: String,
		val kind: String,
	)

	/**
	 * Everything one export writes.
	 *
	 * @property rowTimes The memory watcher's sample times, oldest first. They are the rows, because
	 * memory is the one series always being recorded.
	 * @property sampleIntervalMillis How often the watchers sample. Required rather than defaulted,
	 * because it decides which annotations are near enough to a row to be written on it and a
	 * default would pick that bound for a caller who never considered it.
	 */
	class Snapshot(
		val rowTimes: LongArray,
		val sampleIntervalMillis: Long,
		val memory: Map<String, Series>,
		val networkReceived: Series = Series.EMPTY,
		val networkTransmitted: Series = Series.EMPTY,
		val temperature: Series = Series.EMPTY,
		val power: Series = Series.EMPTY,
		val thermal: Series = Series.EMPTY,
		val annotations: List<Marker> = emptyList(),
	) {
		/**
		 * Whether this snapshot has any sample to report.
		 *
		 * [write] always produces a file, header included, because the export button was asked for
		 * one whatever the state of the buffers. Sending one is a different question: ADFA-5534
		 * attaches the file to feedback only when there is something in it, rather than posting an
		 * empty attachment, and ADFA-5526 will want the same of a crash report. This is how a caller
		 * asks.
		 */
		val hasRows: Boolean get() = rowTimes.any { it != NO_SAMPLE }
	}

	/**
	 * Writes [snapshot] to [out], timestamps in [zone].
	 *
	 * The header is always written, even when nothing has been sampled. A file that exists and
	 * reports no rows is easier for a consumer to handle than one that may or may not be there, and
	 * the empty case is not exotic: changing the sampling rate clears every buffer.
	 */
	fun write(
		snapshot: Snapshot,
		zone: ZoneId,
		out: Appendable,
	) {
		out.append(HEADER.joinToString(",", transform = ::quote)).append('\n')

		val markerRows = markerRows(snapshot)
		val row = StringBuilder()
		snapshot.rowTimes.forEachIndexed { i, at ->
			if (at == NO_SAMPLE) {
				return@forEachIndexed
			}

			row.setLength(0)
			row.append(quote(formatTime(at, zone)))
			MEMORY_COLUMNS.forEach { name -> row.append(',').append(number(snapshot.memory[name]?.at(i))) }
			row.append(',').append(number(snapshot.networkReceived.at(i)))
			row.append(',').append(number(snapshot.networkTransmitted.at(i)))
			row.append(',').append(number(snapshot.temperature.at(i)))
			row.append(',').append(number(snapshot.power.at(i)))
			row.append(',').append(number(snapshot.thermal.at(i)))
			val marker = markerRows[i]
			row.append(',').append(marker?.let { quote(it.label) } ?: "")
			row.append(',').append(marker?.let { quote(it.kind) } ?: "")
			out.append(row).append('\n')
		}
	}

	/**
	 * An annotation's time, moved onto the clock the samples are stamped with.
	 *
	 * [MetricsAnnotationStore] records on [android.os.SystemClock.elapsedRealtime], which is
	 * monotonic and immune to the wall clock being set, and is what the chart wants: it only ever
	 * asks how long ago something happened. A file has to say *when*, so the samples carry epoch
	 * milliseconds, and the two cannot be compared without this.
	 *
	 * Mixing them is not a small error. A monotonic time is a few hours since boot and an epoch time
	 * is decades, so every row looks about equally far from the marker and the nearest-row search
	 * lands on whichever row has the smallest number -- the oldest one in the buffer, every time.
	 *
	 * @param monotonicAtMillis The time the store recorded.
	 * @param nowEpochMillis Now, on the samples' clock.
	 * @param nowMonotonicMillis Now, on the store's clock. Read as close together as possible.
	 */
	fun epochFor(
		monotonicAtMillis: Long,
		nowEpochMillis: Long,
		nowMonotonicMillis: Long,
	): Long = monotonicAtMillis + (nowEpochMillis - nowMonotonicMillis)

	/** [atMillis] as ISO 8601 in [zone]. */
	fun formatTime(
		atMillis: Long,
		zone: ZoneId,
	): String = TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(atMillis).atZone(zone))

	/**
	 * The row each marker belongs on, resolved once for the whole file.
	 *
	 * A marker goes on the row whose sample is nearest it in time. An annotation is recorded when
	 * something happened, not when a sample was taken, so requiring an exact match would drop
	 * almost all of them; and doing this per row rather than once would walk every marker against
	 * every row, which at ten thousand of each is not a cost worth paying for a button.
	 *
	 * A marker further than one sampling interval from its nearest row is dropped rather than
	 * pulled onto it. Sampling at a fixed interval leaves every marker that happened while the
	 * buffer was filling within half an interval of some sample, so a greater distance means the
	 * marker falls outside the sampled window -- an annotation older than the buffer reaches, which
	 * is the ordinary case in a long session. Without the cap every one of those lands on row 0,
	 * where [MutableMap.putIfAbsent] keeps the first and drops the rest: the file would carry one
	 * arbitrary ancient marker on its oldest row and lose the others silently. The chart has both
	 * guards already -- it asks the store only for the annotations in the visible span, and drops
	 * any whose x falls before the first sample.
	 *
	 * Where two markers land on one row the earlier wins, and the later is dropped rather than
	 * silently overwriting it -- the file has one annotation column per row by definition.
	 */
	private fun markerRows(snapshot: Snapshot): Map<Int, Marker> {
		if (snapshot.annotations.isEmpty()) {
			return emptyMap()
		}

		val sampled = snapshot.rowTimes.withIndex().filter { it.value != NO_SAMPLE }
		if (sampled.isEmpty()) {
			return emptyMap()
		}

		val rows = mutableMapOf<Int, Marker>()
		snapshot.annotations.sortedBy { it.atMillis }.forEach { marker ->
			val nearest = sampled.minByOrNull { abs(it.value - marker.atMillis) } ?: return@forEach
			if (abs(nearest.value - marker.atMillis) > snapshot.sampleIntervalMillis) {
				return@forEach
			}
			rows.putIfAbsent(nearest.index, marker)
		}
		return rows
	}

	private fun number(value: Long?): String = value?.toString() ?: ""

	/**
	 * The characters that make a spreadsheet read a cell as a formula rather than as text.
	 *
	 * Tab and carriage return are here because a leading one of either is stripped on import, which
	 * exposes whatever follows it: a cell of "\t=cmd" is a formula too.
	 */
	private val FORMULA_LEAD = charArrayOf('=', '+', '-', '@', '\t', '\r')

	/**
	 * A CSV string cell.
	 *
	 * Quoted per the format's rule (b), with any quote inside it doubled -- a task name is text the
	 * IDE was given, and nothing guarantees it has no quotes in it.
	 *
	 * A cell beginning with one of [FORMULA_LEAD] additionally gets a leading apostrophe. Quoting
	 * alone does not stop a spreadsheet evaluating the cell on import, and the annotation columns
	 * carry Gradle task names taken from the user's own build script -- into a file ADFA-5526 and
	 * ADFA-5534 attach to crash reports and to feedback, which a support engineer then opens. The
	 * apostrophe is part of the cell as written, so a reader parsing this file back has to strip it.
	 *
	 * The numeric columns do not come through here. They are written by [number], where a negative
	 * value has to stay a number rather than become text with a quote in front of it.
	 */
	private fun quote(value: String): String {
		val guarded = if (value.isNotEmpty() && value[0] in FORMULA_LEAD) "'" + value else value
		return "\"" + guarded.replace("\"", "\"\"") + "\""
	}
}
