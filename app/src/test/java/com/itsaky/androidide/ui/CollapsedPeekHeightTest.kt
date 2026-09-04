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

package com.itsaky.androidide.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class CollapsedPeekHeightTest {
	@Test
	fun `peek covers the header and the chrome above it`() {
		// The A56 case: a 100dp header under 101px of status-bar padding and a 7px divider row.
		assertEquals(389, collapsedPeekHeightPx(281.25f, 108, isSearchModeActive = false))
	}

	@Test
	fun `a taller header raises the peek by the same amount`() {
		assertEquals(404, collapsedPeekHeightPx(296f, 108, isSearchModeActive = false))
	}

	@Test
	fun `no chrome above the header leaves the peek at the header height`() {
		assertEquals(281, collapsedPeekHeightPx(281.25f, 0, isSearchModeActive = false))
	}

	@Test
	fun `search mode hides the sheet whatever the header measures`() {
		assertEquals(0, collapsedPeekHeightPx(296f, 108, isSearchModeActive = true))
	}
}
