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

package com.itsaky.androidide.memprof

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Collects phases and repeated work into a plain-text report a person can read without tooling.
 *
 * A phase still running when the report is rendered is shown as in progress, measured up to the
 * moment of rendering, so a report written just before the process dies still says where it was.
 * All members are thread-safe.
 */
class MemprofReport(
	private val processStartMs: Long,
	private val heapLimitBytes: Long,
	private val zone: ZoneId = ZoneId.systemDefault(),
) {
	private val phases = mutableListOf<Phase>()
	private val repeatedWork = linkedMapOf<String, RepeatedWork>()
	private var heapPeakBytes = 0L
	private var heapPeakDuring: String? = null

	/** Whether any phase has started and not yet ended. */
	val hasOpenPhases: Boolean
		@Synchronized get() = phases.any { it.end == null }

	/** Records the start of the phase [title], returning the handle to end it with. */
	@Synchronized
	fun phaseStarted(
		title: String,
		nowMs: Long,
		counters: HeapCounters,
	): Phase {
		val phase = Phase(title, nowMs, counters)
		phases += phase
		sampled(counters.usedBytes)
		return phase
	}

	/**
	 * Records the end of [phase], with the [details] attached to it while it ran.
	 *
	 * @param completed False when the phase was abandoned, which the report shows as stopped early.
	 */
	@Synchronized
	fun phaseEnded(
		phase: Phase,
		nowMs: Long,
		counters: HeapCounters,
		details: Map<String, Long>,
		completed: Boolean = true,
	) {
		sampled(counters.usedBytes)
		phase.endMs = nowMs
		phase.end = counters
		phase.details = details
		phase.completed = completed
	}

	/** Records one finished instance of the repeated work [name]. */
	@Synchronized
	fun sectionEnded(
		name: String,
		detail: String?,
		durationMs: Long,
		allocatedBytes: Long,
	) {
		repeatedWork.getOrPut(name) { RepeatedWork(name) }.add(detail, durationMs, allocatedBytes)
	}

	/** Records a heap-in-use reading, which is how peaks between phase boundaries are seen. */
	@Synchronized
	fun sampled(usedBytes: Long) {
		for (phase in phases) {
			if (phase.end == null) {
				phase.peakUsedBytes = maxOf(phase.peakUsedBytes, usedBytes)
			}
		}

		if (usedBytes > heapPeakBytes) {
			heapPeakBytes = usedBytes
			heapPeakDuring = phases.lastOrNull { it.end == null }?.title
		}
	}

	/** Renders the report as of [nowMs], measuring phases still in progress against [counters]. */
	@Synchronized
	fun render(
		nowMs: Long,
		counters: HeapCounters,
	): String =
		buildString {
			appendLine("Code On The Go memory profile")
			appendLine("Process started ${timestamp(processStartMs)}, heap limit ${megabytes(heapLimitBytes)}")
			appendLine("Updated ${timestamp(nowMs)}, ${seconds(nowMs - processStartMs)} after start")
			appendPhases(nowMs, counters)
			appendRepeatedWork()
			appendLine()
			append("Heap peak ${megabytes(heapPeakBytes)} of ${megabytes(heapLimitBytes)}")
			appendLine(heapPeakDuring?.let { ", during \"$it\"" } ?: ", outside any phase")
		}

	private fun StringBuilder.appendPhases(
		nowMs: Long,
		counters: HeapCounters,
	) {
		appendLine()
		appendLine("Phases")
		if (phases.isEmpty()) {
			appendLine("  none yet")
			return
		}

		val header =
			listOf("Phase", "Start", "Time", "Allocated", "Net retained", "Peak heap", "Blocking GCs", "")
		appendTable(listOf(header) + phases.map { it.row(nowMs, counters) })
	}

	private fun StringBuilder.appendRepeatedWork() {
		if (repeatedWork.isEmpty()) {
			return
		}

		appendLine()
		appendLine("Repeated work")
		val header = listOf("Work", "Count", "Total time", "Allocated", "Largest single instance")
		appendTable(listOf(header) + repeatedWork.values.map { it.row() })
	}

	private fun Phase.row(
		nowMs: Long,
		counters: HeapCounters,
	): List<String> {
		val until = end ?: counters
		val peak = maxOf(peakUsedBytes, if (end == null) counters.usedBytes else 0L)
		val notes =
			when {
				end == null -> "(in progress)"
				!completed -> (listOf("(stopped early)") + details.entries.map { detail(it) }).joinToString(" ")
				else -> details.entries.joinToString(" ") { detail(it) }
			}
		return listOf(
			title,
			"+" + seconds(startMs - processStartMs),
			seconds((endMs ?: nowMs) - startMs),
			megabytes(until.allocatedBytes - start.allocatedBytes),
			megabytes(until.netRetainedBytes - start.netRetainedBytes),
			megabytes(peak),
			"%,d".format(Locale.US, until.blockingGcCount - start.blockingGcCount),
			notes,
		)
	}

	private fun RepeatedWork.row(): List<String> =
		listOf(
			name,
			"%,d".format(Locale.US, count),
			seconds(totalMs),
			megabytes(totalAllocatedBytes),
			"${largestDetail ?: "unnamed"}: ${megabytes(largestAllocatedBytes)} in ${seconds(largestMs)}",
		)

	private fun StringBuilder.appendTable(rows: List<List<String>>) {
		val widths = rows.first().indices.map { column -> rows.maxOf { it[column].length } }
		for (row in rows) {
			val cells =
				row.mapIndexed { column, cell ->
					if (column == 0 || column == row.lastIndex) cell.padEnd(widths[column]) else cell.padStart(widths[column])
				}
			appendLine("  " + cells.joinToString("  ").trimEnd())
		}
	}

	private fun detail(entry: Map.Entry<String, Long>): String =
		if (entry.key.endsWith("bytes", ignoreCase = true)) {
			"${entry.key}=${megabytes(entry.value)}"
		} else {
			"${entry.key}=${"%,d".format(Locale.US, entry.value)}"
		}

	private fun timestamp(epochMs: Long): String = TIMESTAMP.format(Instant.ofEpochMilli(epochMs).atZone(zone))

	/** A phase of the report; only [MemprofReport] reads or changes its state. */
	class Phase internal constructor(
		internal val title: String,
		internal val startMs: Long,
		internal val start: HeapCounters,
	) {
		internal var endMs: Long? = null
		internal var end: HeapCounters? = null
		internal var details: Map<String, Long> = emptyMap()
		internal var completed: Boolean = true
		internal var peakUsedBytes: Long = start.usedBytes
	}

	private class RepeatedWork(
		val name: String,
	) {
		var count = 0L
		var totalMs = 0L
		var totalAllocatedBytes = 0L
		var largestDetail: String? = null
		var largestAllocatedBytes = -1L
		var largestMs = 0L

		fun add(
			detail: String?,
			durationMs: Long,
			allocatedBytes: Long,
		) {
			count++
			totalMs += durationMs
			totalAllocatedBytes += allocatedBytes
			if (allocatedBytes > largestAllocatedBytes) {
				largestDetail = detail
				largestAllocatedBytes = allocatedBytes
				largestMs = durationMs
			}
		}
	}

	private companion object {
		val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US)

		fun megabytes(bytes: Long): String = "%,d MB".format(Locale.US, Math.round(bytes / (1024.0 * 1024.0)))

		fun seconds(ms: Long): String = "%.1f s".format(Locale.US, ms / 1000.0)
	}
}
