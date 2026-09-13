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

import android.os.Debug
import android.os.Debug.MemoryInfo
import android.os.Process
import androidx.annotation.VisibleForTesting
import com.itsaky.androidide.BuildConfig
import com.termux.shared.reflection.ReflectionUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Reads one watched process's total memory footprint, in kB (ADFA-5574).
 *
 * A seam with two implementations, because the processes the metrics carousel plots are not alike
 * and reading them all the same way costs the same as reading the most expensive one, three times.
 */
fun interface ProcessMemoryReader {
	/**
	 * This process's footprint in kB, or [ProcessMemoryReaders.UNAVAILABLE] if it could not be
	 * read.
	 *
	 * @param scratch A reusable [MemoryInfo]. Readers that do not need one ignore it; it is a
	 *   parameter rather than an allocation because this runs on every sample.
	 */
	fun totalKb(
		pid: Int,
		scratch: MemoryInfo,
	): Int
}

/**
 * Picks the cheapest reader that is still correct for a given process.
 *
 * The IDE is a Zygote fork and has GPU memory; the tooling server and the Gradle daemon are plain
 * OpenJDK processes exec'd from the app's Termux prefix and have none. Measured on a Pixel 6 Pro:
 * for the JVMs `smaps_rollup` and `Debug.getMemoryInfo` agree to 0.009% (66,018 against 66,012 kB)
 * while the rollup costs 13.4ms against 31.4ms; for the IDE the rollup reads ~124MB low, because
 * `dumpsys meminfo` accounts EGL mtrack 89MB and GL mtrack 36MB through the memtrack HAL rather
 * than through `/proc/pid/smaps`, where a rollup cannot see them. That is 23% of the IDE's total,
 * so the IDE keeps the expensive read.
 */
object ProcessMemoryReaders {
	/** Returned when a process's footprint could not be read at all. */
	const val UNAVAILABLE = -1

	private val log = LoggerFactory.getLogger(ProcessMemoryReaders::class.java)

	/**
	 * Whether this kernel offers a rollup at all.
	 *
	 * Checked once. `smaps_rollup` arrived in Linux 4.14, so Android 10 in practice, and minSdk
	 * here is 28 -- a device below that gets the reflective read for everything, which is what it
	 * had before.
	 */
	@VisibleForTesting
	internal val isRollupSupported: Boolean by lazy {
		File("/proc/self/smaps_rollup").exists()
	}

	/**
	 * The reader for [pid], decided once when a process starts being watched.
	 *
	 * `pid == Process.myPid()` is the whole test, and it costs nothing: the only Zygote-forked
	 * process the carousel plots is the app itself. Nothing has to inspect `/proc` to find out.
	 */
	fun chooseReader(pid: Int): ProcessMemoryReader =
		chooseReader(pid, Process.myPid(), isRollupSupported).also { chosen ->
			if (BuildConfig.DEBUG && chosen === SmapsRollupReader) {
				// Off the caller's thread. The only caller is watchProcess, and for the Gradle
				// daemon it reaches there from a main-dispatched build callback -- so this
				// sequential scan of a JVM's maps file (93,120 lines for the IDE's own) ran on the
				// UI thread, in exactly the build a developer is watching. A StrictMode
				// DiskReadViolation, and visible jank, for a debug-only warning.
				diagnosticsScope.launch { warnIfProcessHasGraphicsMemory(pid) }
			}
		}

	/** Debug-only diagnostics, off whatever thread started watching a process. */
	private val diagnosticsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

	/**
	 * Complains if a process given the cheap read turns out to be an Android runtime process.
	 *
	 * The rule rests on an assumption about the three processes plotted today: only the app's own
	 * is Zygote-forked, and only a Zygote fork has graphics memory a rollup cannot see. Add a
	 * fourth watched process that is one, and its line would quietly read about a quarter low --
	 * the failure this whole ticket is about, arriving silently. One maps scan when a process starts
	 * being watched, in debug builds only, turns that into something someone notices.
	 */
	private fun warnIfProcessHasGraphicsMemory(pid: Int) {
		val isRuntimeProcess =
			runCatching {
				File("/proc/$pid/maps").useLines { lines ->
					lines.any { it.contains("libandroid_runtime.so") }
				}
			}.getOrDefault(false)
		if (isRuntimeProcess) {
			log.error(
				"pid {} maps libandroid_runtime.so, so it may hold graphics memory that " +
					"smaps_rollup cannot see. Its memory line will read low. See ADFA-5574.",
				pid,
			)
		}
	}

	@VisibleForTesting
	internal fun chooseReader(
		pid: Int,
		ownPid: Int,
		rollupSupported: Boolean,
	): ProcessMemoryReader =
		if (pid == ownPid || !rollupSupported) {
			DebugMemoryInfoReader
		} else {
			SmapsRollupReader
		}

	internal fun logFallback(pid: Int) {
		log.warn("smaps_rollup unreadable for pid {}; falling back to Debug.getMemoryInfo", pid)
	}
}

/**
 * `Debug.getMemoryInfo`, reached reflectively.
 *
 * The only source that includes graphics memory, which is why the app's own process uses it.
 * Reflective because `ActivityManager.getProcessMemoryInfo` is rate-limited and internally calls
 * this, so going straight to it sidesteps the limit.
 */
object DebugMemoryInfoReader : ProcessMemoryReader {
	private val getMemoryInfo: java.lang.reflect.Method by lazy {
		checkNotNull(
			ReflectionUtils.getDeclaredMethod(
				Debug::class.java,
				"getMemoryInfo",
				Int::class.javaPrimitiveType,
				MemoryInfo::class.java,
			),
		) {
			"Unable to find getMemoryInfo method in android.os.Debug class"
		}
	}

	override fun totalKb(
		pid: Int,
		scratch: MemoryInfo,
	): Int {
		ReflectionUtils.invokeMethod(getMemoryInfo, null, pid, scratch)

		// From https://developer.android.com/tools/dumpsys#meminfo
		// "PSS is a good measure for the actual RAM weight of a process and for comparison
		// against the RAM use of other processes and the total available RAM."
		return scratch.totalPss
	}
}

/**
 * The kernel's own PSS total, from `/proc/pid/smaps_rollup`.
 *
 * The `Pss:` field alone, not `Pss` plus `SwapPss`. Measured against `Debug.getMemoryInfo` on a
 * JVM process, `Pss` alone was 6kB *higher* out of 66MB, so adding swap would move it further
 * away rather than closer.
 *
 * Cheaper than walking `/proc/pid/smaps` because the kernel does the summation and hands back one
 * short file rather than one stanza per mapping -- 22 lines against 93,120 for the IDE. The kernel
 * still walks every mapping to compute it, which is why this is 2.3x cheaper and not 40x.
 */
object SmapsRollupReader : ProcessMemoryReader {
	override fun totalKb(
		pid: Int,
		scratch: MemoryInfo,
	): Int =
		runCatching {
			File("/proc/$pid/smaps_rollup").useLines { lines -> pssKbFrom(lines) }
		}.getOrDefault(ProcessMemoryReaders.UNAVAILABLE)

	/**
	 * Picks the rollup's `Pss` out of [lines] and reads its value.
	 *
	 * Separate from [totalKb] so both halves can be tested: choosing the right line matters as much
	 * as parsing it, and a test that does its own line-picking would pin only the parse.
	 */
	@VisibleForTesting
	internal fun pssKbFrom(lines: Sequence<String>): Int =
		lines
			.firstOrNull { it.startsWith(PSS_PREFIX) }
			?.let(::firstIntOrUnavailable)
			?: ProcessMemoryReaders.UNAVAILABLE

	/**
	 * The first run of digits in a line, without allocating.
	 *
	 * `Pss:              425176 kB`. Hand-scanned rather than split, because this runs on every
	 * sample for every watched process.
	 */
	private fun firstIntOrUnavailable(line: String): Int {
		var value = 0
		var seen = false
		for (c in line) {
			if (c in '0'..'9') {
				value = value * 10 + (c - '0')
				seen = true
			} else if (seen) {
				break
			}
		}
		return if (seen) value else ProcessMemoryReaders.UNAVAILABLE
	}

	/**
	 * Deliberately with the colon. The rollup also carries `Pss_Anon`, `Pss_File`, `Pss_Shmem` and
	 * `Pss_Dirty`, and a prefix of `Pss` alone would match whichever came first.
	 */
	private const val PSS_PREFIX = "Pss:"
}
