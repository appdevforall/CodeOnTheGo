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

package com.itsaky.androidide.logs

import com.itsaky.androidide.models.LogFilter
import com.itsaky.androidide.utils.ILogger

/**
 * A bounded, thread-safe history of log entries. Retains the level metadata that is
 * lost once lines are flattened into the editor, so the view can be re-rendered when
 * the user changes the active [LogFilter].
 *
 * @param trimOnEntryCount Trim the buffer once it grows past this many entries.
 * @param maxEntryCount The number of most-recent entries kept after a trim.
 */
class LogBuffer(
	private val trimOnEntryCount: Int,
	private val maxEntryCount: Int,
) {
	/**
	 * A single submitted log line.
	 *
	 * @property seq Monotonically increasing sequence number, used to stitch a
	 *   buffer snapshot together with the live entry stream without gaps or duplicates.
	 * @property text The rendered line, always terminated with a newline.
	 */
	data class Entry(
		val seq: Long,
		val level: ILogger.Level?,
		val text: String,
	)

	init {
		require(maxEntryCount in 1..trimOnEntryCount) {
			"maxEntryCount must be in 1..trimOnEntryCount"
		}
	}

	private val entries = ArrayDeque<Entry>()
	private var nextSeq = 1L

	@Synchronized
	fun append(
		level: ILogger.Level?,
		text: String,
	): Entry {
		val entry = Entry(nextSeq++, level, text)
		entries.addLast(entry)
		if (entries.size > trimOnEntryCount) {
			repeat(entries.size - maxEntryCount) {
				entries.removeFirst()
			}
		}
		return entry
	}

	/**
	 * Render all entries matching [filter] into a single string.
	 *
	 * @return The rendered text and the sequence number of the newest entry in the
	 *   buffer at snapshot time (0 if the buffer is empty), regardless of whether
	 *   that entry matched the filter.
	 */
	@Synchronized
	fun snapshotFiltered(filter: LogFilter): Pair<String, Long> {
		val lastSeq = entries.lastOrNull()?.seq ?: 0L
		val text =
			buildString {
				for (entry in entries) {
					if (filter.matches(entry.level, entry.text)) {
						append(entry.text)
					}
				}
			}
		return text to lastSeq
	}

	@Synchronized
	fun snapshotAll(): String =
		buildString {
			for (entry in entries) {
				append(entry.text)
			}
		}

	@Synchronized
	fun clear() {
		entries.clear()
	}
}
