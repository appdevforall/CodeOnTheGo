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

import androidx.annotation.VisibleForTesting
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Destinations for one metrics snapshot, allocated once so that taking one needs no memory.
 *
 * A crash handler is a poor place to ask for memory: the crash being reported may be the heap
 * running out, and a handler that throws replaces a useful report with a useless one. Snapshotting
 * the watchers otherwise takes eleven fresh arrays -- around 300KB at the retained length -- so the
 * arrays are taken at startup instead, when failing to get them is survivable and obvious.
 *
 * This removes the largest single allocation on that path, not all of it: writing the file still
 * takes a Deflater and its buffer, an 8KB writer buffer and a String per cell. So it improves the
 * odds of getting a report out under memory pressure rather than guaranteeing one, and under a
 * genuine OutOfMemoryError the write can still fail and the attachment still be dropped. Removing
 * the rest means streaming the CSV without per-cell Strings, which is a bigger change than this.
 *
 * Held for the life of the process, which is the trade: this is memory reserved against a crash that
 * may never come, in a process that is already a fat target for the low-memory killer. It is paid
 * for by [MemoryUsageWatcher.MAX_USAGE_ENTRIES] coming down at the same time -- the live buffers plus
 * these cost less than the live buffers alone did before (ADFA-5526).
 *
 * Not thread-confined but single-use at a time: [claim] hands it to one caller and [release] gives it
 * back. A caller that cannot claim it allocates for itself rather than waiting or sharing, because
 * two writers into one array is a scrambled file and a crash must not block on an export.
 */
class MetricsScratch(
	@VisibleForTesting internal val entries: Int,
	memorySeries: Int,
) {
	private val inUse = AtomicBoolean(false)

	val memoryTimes = LongArray(entries)
	val memoryValues: List<LongArray> = List(memorySeries) { LongArray(entries) }
	val networkTimes = LongArray(entries)
	val networkReceived = LongArray(entries)
	val networkTransmitted = LongArray(entries)
	val powerTimes = LongArray(entries)
	val temperature = LongArray(entries)
	val power = LongArray(entries)
	val thermal = LongArray(entries)

	/** Takes this scratch, or returns false if something else already has it. */
	fun claim(): Boolean = inUse.compareAndSet(false, true)

	fun release() {
		inUse.set(false)
	}

	companion object {
		/**
		 * The process-wide scratch, or `null` before [install] or if it could not be allocated.
		 *
		 * A crash arrives on whatever thread threw, from anywhere in the process, so this cannot
		 * live on an activity-scoped ViewModel the way the watchers do.
		 */
		@Volatile
		var instance: MetricsScratch? = null
			private set

		/**
		 * Allocates the process-wide scratch. Call once, from application startup.
		 *
		 * Failure is not fatal and not worth retrying: the crash path simply allocates for itself,
		 * which is what it did before this existed.
		 */
		fun install(
			entries: Int = sharedRetention(),
			memorySeries: Int = MetricsCsv.MEMORY_COLUMNS.size,
		) {
			if (instance != null) {
				return
			}
			instance = runCatching { MetricsScratch(entries, memorySeries) }.getOrNull()
		}

		/**
		 * The one retention all three watchers keep, or a throw naming the ones that disagree.
		 *
		 * One buffer size is handed to all three, and `ShiftedLongArray.copyInto` require()s an
		 * *exact* match -- so `maxOf` of the three was no protection at all: it picks a size two of
		 * them would reject the moment they stopped agreeing. That throw lands inside
		 * `MetricsCrashAttachment`'s runCatching, where it is swallowed, and every crash report and
		 * feedback send silently loses its metrics -- the failure this class exists to prevent.
		 *
		 * Failing here instead makes divergence a loud startup failure with the numbers in the
		 * message, not a quiet hole in diagnostics nobody notices until they need one. The
		 * alternative, sizing a destination per watcher, is the right answer if these ever
		 * legitimately differ; today they are one number and this says so.
		 */
		@VisibleForTesting
		internal fun sharedRetention(
			memory: Int = MemoryUsageWatcher.MAX_USAGE_ENTRIES,
			network: Int = NetworkUsageWatcher.MAX_USAGE_ENTRIES,
			power: Int = PowerUsageWatcher.MAX_USAGE_ENTRIES,
		): Int {
			require(memory == network && network == power) {
				"The watchers must retain the same number of samples to share one scratch buffer, " +
					"but memory=$memory, network=$network, power=$power"
			}
			return memory
		}

		@VisibleForTesting
		internal fun resetForTesting() {
			instance = null
		}
	}
}
