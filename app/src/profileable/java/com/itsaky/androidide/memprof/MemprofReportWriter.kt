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
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps the report file current and prints the report to logcat when asked.
 *
 * The file is rewritten in full on every write, through a temporary file and a rename, so a process
 * that dies mid-write leaves the previous complete report rather than a torn one. All file I/O runs
 * on one background thread, so callers on any thread, the main thread included, never block on it.
 */
internal class MemprofReportWriter(
	private val report: MemprofReport,
	private val file: () -> File,
) {
	private val dirty = AtomicBoolean(false)

	@Volatile
	private var lastWriteMs = 0L

	/** Marks the report as changed, to be written by the next [writeIfDirty]. */
	fun markDirty() {
		dirty.set(true)
	}

	/**
	 * Writes the report now.
	 *
	 * Rendering happens on the io thread, not the caller's, so that two threads writing back to
	 * back queue in the order they render: rendering on the caller's thread let two callers queue
	 * in the opposite order they rendered, so an older report could land as the final file.
	 */
	fun write() {
		dirty.set(false)
		io.execute { writeFile(render()) }
	}

	/** Writes the report if it changed and the last write is at least [minIntervalMs] old. */
	fun writeIfDirty(minIntervalMs: Long) {
		if (System.currentTimeMillis() - lastWriteMs >= minIntervalMs && dirty.get()) {
			write()
		}
	}

	/** Prints the report to logcat, one line per log entry, so a reader sees the table intact. */
	fun log() {
		render().lineSequence().forEach { line -> logger.info("MEMPROF|report| {}", line) }
	}

	private fun render(): String = report.render(System.currentTimeMillis(), RuntimeHeapCounters.read())

	private fun writeFile(text: String) {
		lastWriteMs = System.currentTimeMillis()
		runCatching {
			// file() creates the report directory; this is the only place that does.
			val target = file()
			val temporary = File(target.parentFile, target.name + ".tmp")
			temporary.writeText(text)
			check(temporary.renameTo(target)) { "rename to $target failed" }
		}.onFailure { failure -> logger.error("MEMPROF|report_write_failed|{}", lastWriteMs, failure) }
	}

	private companion object {
		val logger = LoggerFactory.getLogger(MemprofReportWriter::class.java)

		val io =
			Executors.newSingleThreadExecutor { runnable ->
				Thread(runnable, "memprof-report").apply { isDaemon = true }
			}
	}
}
