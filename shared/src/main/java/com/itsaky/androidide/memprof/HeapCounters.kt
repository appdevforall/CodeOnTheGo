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

/**
 * One reading of the runtime's heap counters.
 *
 * [allocatedBytes], [freedBytes] and [blockingGcCount] are cumulative for the life of the process,
 * so the difference between two readings is what happened between them.
 */
data class HeapCounters(
	val allocatedBytes: Long,
	val freedBytes: Long,
	val blockingGcCount: Long,
	val usedBytes: Long,
) {
	/** Bytes allocated and not yet freed, by the cumulative counters. */
	val netRetainedBytes: Long
		get() = allocatedBytes - freedBytes
}
