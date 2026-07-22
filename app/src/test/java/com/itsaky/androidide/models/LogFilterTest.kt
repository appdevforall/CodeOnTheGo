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

package com.itsaky.androidide.models

import com.itsaky.androidide.utils.ILogger
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogFilterTest {
	@Test
	fun `default filter matches everything and is inactive`() {
		val filter = LogFilter.NONE
		assertFalse(filter.isActive)
		assertTrue(filter.matches(ILogger.Level.VERBOSE, "anything"))
		assertTrue(filter.matches(null, "anything"))
	}

	@Test
	fun `level filter hides disabled levels`() {
		val filter = LogFilter(enabledLevels = setOf(ILogger.Level.ERROR))
		assertTrue(filter.isActive)
		assertTrue(filter.matches(ILogger.Level.ERROR, "boom"))
		assertFalse(filter.matches(ILogger.Level.DEBUG, "noise"))
		assertFalse(filter.matches(ILogger.Level.WARNING, "warn"))
	}

	@Test
	fun `lines without level always pass the level check`() {
		val filter = LogFilter(enabledLevels = setOf(ILogger.Level.ERROR))
		assertTrue(filter.matches(null, "gradle output line"))
	}

	@Test
	fun `text filter is case-insensitive contains`() {
		val filter = LogFilter(text = "NullPointer")
		assertTrue(filter.isActive)
		assertTrue(filter.matches(null, "java.lang.nullpointerexception at ..."))
		assertTrue(filter.matches(ILogger.Level.ERROR, "NULLPOINTER"))
		assertFalse(filter.matches(null, "IllegalStateException"))
	}

	@Test
	fun `level and text filters combine`() {
		val filter = LogFilter(enabledLevels = setOf(ILogger.Level.ERROR), text = "boom")
		assertTrue(filter.matches(ILogger.Level.ERROR, "boom happened"))
		assertFalse(filter.matches(ILogger.Level.ERROR, "other error"))
		assertFalse(filter.matches(ILogger.Level.DEBUG, "boom happened"))
	}

	@Test
	fun `empty enabled levels hides all leveled lines but keeps unleveled ones`() {
		val filter = LogFilter(enabledLevels = emptySet())
		assertTrue(filter.isActive)
		assertFalse(filter.matches(ILogger.Level.INFO, "info"))
		assertTrue(filter.matches(null, "plain"))
	}
}
