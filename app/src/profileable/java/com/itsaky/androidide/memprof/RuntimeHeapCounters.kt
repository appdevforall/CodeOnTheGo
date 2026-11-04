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

import android.os.Debug

/** Reads [HeapCounters] from ART, with no GC side effect. */
internal object RuntimeHeapCounters {
	fun read(): HeapCounters {
		val runtime = Runtime.getRuntime()
		return HeapCounters(
			allocatedBytes = stat("art.gc.bytes-allocated"),
			freedBytes = stat("art.gc.bytes-freed"),
			blockingGcCount = stat("art.gc.blocking-gc-count"),
			usedBytes = runtime.totalMemory() - runtime.freeMemory(),
		)
	}

	/** -1 for a stat ART does not expose, matching [AllocationSampler]'s convention for the same counters. */
	private fun stat(key: String): Long = Debug.getRuntimeStat(key)?.toLongOrNull() ?: -1L
}
