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

import android.content.Context
import android.os.Process
import android.os.SystemClock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/** Wires the profileable variant's measurement together, once per process. */
internal object MemprofSession {
	/** Often enough that a report pulled mid-open is current, rarely enough to cost nothing. */
	private const val REPORT_INTERVAL_MS = 2_000L

	@Volatile
	var writer: MemprofReportWriter? = null
		private set

	/**
	 * Installs the [Memprof] sink and starts the allocation sampler.
	 *
	 * Safe on the main thread: resolving the files directory touches the disk, so it happens on a
	 * background thread, and the report writer does its own I/O on its own thread.
	 */
	fun install(context: Context) {
		val processStartMs = processStartEpochMs()
		val report = MemprofReport(processStartMs, Runtime.getRuntime().maxMemory())
		val startStamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(processStartMs))
		val reportName = "report-$startStamp.txt"
		val reportWriter = MemprofReportWriter(report) { File(memprofDir(context), reportName) }

		writer = reportWriter
		Memprof.sink = ProfileableMemprofSink(report, reportWriter)
		AllocationSampler.onTick = { usedBytes ->
			report.sampled(usedBytes)
			reportWriter.writeIfDirty(REPORT_INTERVAL_MS)
		}
		AllocationSampler.start()

		thread(name = "memprof-init", isDaemon = true) {
			AllocationSampler.dumpDir = memprofDir(context).absolutePath
		}
	}

	private fun memprofDir(context: Context): File = File(context.filesDir, "memprof").apply { mkdirs() }

	private fun processStartEpochMs(): Long {
		val uptimeMs = SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()
		return System.currentTimeMillis() - uptimeMs
	}
}
