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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogBufferTest {
	@Test
	fun `sequence numbers increase monotonically`() {
		val buffer = LogBuffer(trimOnEntryCount = 10, maxEntryCount = 5)
		val first = buffer.append(null, "a\n")
		val second = buffer.append(null, "b\n")
		assertTrue(second.seq > first.seq)
	}

	@Test
	fun `snapshot returns all entries and the last sequence`() {
		val buffer = LogBuffer(trimOnEntryCount = 10, maxEntryCount = 5)
		buffer.append(ILogger.Level.DEBUG, "debug\n")
		val last = buffer.append(ILogger.Level.ERROR, "error\n")

		val (text, lastSeq) = buffer.snapshotFiltered(LogFilter.NONE)
		assertEquals("debug\nerror\n", text)
		assertEquals(last.seq, lastSeq)
	}

	@Test
	fun `snapshot applies the filter but reports the newest seq regardless`() {
		val buffer = LogBuffer(trimOnEntryCount = 10, maxEntryCount = 5)
		buffer.append(ILogger.Level.ERROR, "error\n")
		val last = buffer.append(ILogger.Level.DEBUG, "debug\n")

		val (text, lastSeq) =
			buffer.snapshotFiltered(LogFilter(enabledLevels = setOf(ILogger.Level.ERROR)))
		assertEquals("error\n", text)
		assertEquals(last.seq, lastSeq)
	}

	@Test
	fun `empty buffer snapshots to empty text and seq 0`() {
		val buffer = LogBuffer(trimOnEntryCount = 10, maxEntryCount = 5)
		val (text, lastSeq) = buffer.snapshotFiltered(LogFilter.NONE)
		assertEquals("", text)
		assertEquals(0L, lastSeq)
	}

	@Test
	fun `buffer trims to maxEntryCount once trimOnEntryCount is exceeded`() {
		val buffer = LogBuffer(trimOnEntryCount = 10, maxEntryCount = 5)
		repeat(11) { buffer.append(null, "line$it\n") }

		val (text, _) = buffer.snapshotFiltered(LogFilter.NONE)
		val lines = text.trim().lines()
		assertEquals(5, lines.size)
		assertEquals("line6", lines.first())
		assertEquals("line10", lines.last())
	}

	@Test
	fun `clear empties the buffer but keeps sequence increasing`() {
		val buffer = LogBuffer(trimOnEntryCount = 10, maxEntryCount = 5)
		val beforeClear = buffer.append(null, "a\n")
		buffer.clear()

		assertEquals("", buffer.snapshotAll())
		val afterClear = buffer.append(null, "b\n")
		assertTrue(afterClear.seq > beforeClear.seq)
	}

	@Test
	fun `snapshotAll ignores filters`() {
		val buffer = LogBuffer(trimOnEntryCount = 10, maxEntryCount = 5)
		buffer.append(ILogger.Level.DEBUG, "debug\n")
		buffer.append(ILogger.Level.ERROR, "error\n")
		assertEquals("debug\nerror\n", buffer.snapshotAll())
	}
}
