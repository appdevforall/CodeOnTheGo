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

import org.slf4j.LoggerFactory
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sends [Memprof] events to three places, one per audience.
 *
 * - Perfetto: every phase and section is an async slice, so a recorded trace shows the open as a
 *   labelled timeline beside the GC and allocation tracks.
 * - The [MemprofReport]: a plain-text summary, kept current on disk and printed to logcat whenever
 *   the last open phase ends.
 * - Machine lines in logcat: `PHASE|<marker>|<epochMs>|k=v...` as the memprof harness scripts parse
 *   them, plus `MEMPROF|file|...` per section instance. See [MemprofLines] for the exact shapes.
 */
internal class ProfileableMemprofSink(
	private val report: MemprofReport,
	private val writer: MemprofReportWriter,
) : MemprofSink {
	override fun beginPhase(
		title: String,
		marker: String,
	): MemprofSpan =
		guarded("beginPhase", marker) {
			val start = Start.now()
			val phase = report.phaseStarted(title, start.epochMs, start.counters)
			logger.info("MEMPROF|phase_begin|{}|marker={}|title={}", start.epochMs, marker, title)
			writer.write()
			PhaseSpan(marker, phase, start, AsyncTraceSection(title))
		}

	override fun beginSection(
		name: String,
		detail: String?,
	): MemprofSpan =
		guarded("beginSection", name) {
			val traceName = if (detail == null) name else "$name: ${detail.substringAfterLast('/')}"
			SectionSpan(name, detail, Start.now(), AsyncTraceSection(traceName))
		}

	override fun mark(marker: String) {
		logger.info("PHASE|{}|{}", marker, System.currentTimeMillis())
	}

	/**
	 * Runs [block], turning a failure into a log line and [MemprofSpan.None] instead of letting it
	 * reach the caller: a measurement probe must never break the product path it is measuring.
	 */
	private inline fun guarded(
		op: String,
		label: String,
		block: () -> MemprofSpan,
	): MemprofSpan =
		try {
			block()
		} catch (failure: Throwable) {
			logger.error("MEMPROF|sink_failed|{}|op={}|label={}", System.currentTimeMillis(), op, label, failure)
			MemprofSpan.None
		}

	private class Start(
		val epochMs: Long,
		val counters: HeapCounters,
	) {
		companion object {
			fun now() = Start(System.currentTimeMillis(), RuntimeHeapCounters.read())
		}
	}

	private abstract class RecordingSpan : MemprofSpan {
		private val ended = AtomicBoolean(false)

		/** Insertion order, not hash order, so notes and log lines list details as they were put. */
		protected val details: MutableMap<String, Long> = Collections.synchronizedMap(LinkedHashMap())

		override val isRecording: Boolean = true

		override fun put(
			key: String,
			value: Long,
		) {
			details[key] = value
		}

		/** A snapshot of [details] in insertion order, safe to read after ending. */
		protected fun detailsSnapshot(): Map<String, Long> = synchronized(details) { LinkedHashMap(details) }

		final override fun end() = finish(completed = true)

		final override fun abandon() = finish(completed = false)

		/** A span ending must never break the work it timed, so a failure here is logged and swallowed. */
		private fun finish(completed: Boolean) {
			if (!ended.compareAndSet(false, true)) {
				return
			}
			try {
				onEnd(System.currentTimeMillis(), RuntimeHeapCounters.read(), completed)
			} catch (failure: Throwable) {
				logger.error("MEMPROF|sink_failed|{}|op=end|completed={}", System.currentTimeMillis(), completed, failure)
			}
		}

		abstract fun onEnd(
			nowMs: Long,
			counters: HeapCounters,
			completed: Boolean,
		)
	}

	private inner class PhaseSpan(
		private val marker: String,
		private val phase: MemprofReport.Phase,
		private val start: Start,
		private val trace: AsyncTraceSection,
	) : RecordingSpan() {
		override fun onEnd(
			nowMs: Long,
			counters: HeapCounters,
			completed: Boolean,
		) {
			trace.end()
			val putDetails = detailsSnapshot()
			report.phaseEnded(phase, nowMs, counters, putDetails, completed)
			logger.info(
				if (completed) {
					MemprofLines.phaseLine(marker, nowMs, putDetails)
				} else {
					MemprofLines.phaseAbandonedLine(marker, nowMs, putDetails)
				},
			)
			logger.info(
				MemprofLines.phaseEndLine(
					marker,
					nowMs,
					durationMs = nowMs - start.epochMs,
					allocBytes = counters.allocatedBytes - start.counters.allocatedBytes,
				),
			)
			writer.write()
			if (!report.hasOpenPhases) {
				writer.log()
			}
		}
	}

	private inner class SectionSpan(
		private val name: String,
		private val detail: String?,
		private val start: Start,
		private val trace: AsyncTraceSection,
	) : RecordingSpan() {
		override fun onEnd(
			nowMs: Long,
			counters: HeapCounters,
			completed: Boolean,
		) {
			trace.end()
			val durationMs = nowMs - start.epochMs
			val allocatedBytes = counters.allocatedBytes - start.counters.allocatedBytes
			if (completed) {
				/*
				 * An abandoned (preempted, re-queued) instance is not repeated work: the file it
				 * touched will be indexed again and counted then, so counting it here too would
				 * double it in the "Repeated work" table.
				 */
				report.sectionEnded(name, detail, durationMs, allocatedBytes)
			}
			logger.info(MemprofLines.fileLine(nowMs, allocatedBytes, durationMs, detail, name, completed))
			writer.markDirty()
		}
	}

	private companion object {
		val logger = LoggerFactory.getLogger(ProfileableMemprofSink::class.java)
	}
}
