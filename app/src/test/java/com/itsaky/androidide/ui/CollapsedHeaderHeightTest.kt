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

class CollapsedHeaderHeightTest {
	// The A06 at 300dpi: a 100dp floor, 43px of status-bar padding and a 5px divider row above the header.
	private val floor = 187.5f
	private val chrome = 48

	@Test
	fun `a block that fits in the visible part of the floor keeps the floor`() {
		// Font scale 2.0, one-line status (57px) plus the hint (51px): 108 + 48 < 187.5.
		assertEquals(187.5f, collapsedHeaderHeightPx(floor, 108f, chrome, statusShown = true))
	}

	@Test
	fun `a block taller than the visible part grows the header by the hidden strip`() {
		// Font scale 2.0, three-line status (171px) plus the hint (51px).
		assertEquals(270f, collapsedHeaderHeightPx(floor, 222f, chrome, statusShown = true))
	}

	@Test
	fun `a block that just fits the visible part stays at the floor`() {
		assertEquals(floor, collapsedHeaderHeightPx(floor, floor - chrome, chrome, statusShown = true))
		assertEquals(floor + 1f, collapsedHeaderHeightPx(floor, floor - chrome + 1f, chrome, statusShown = true))
	}

	@Test
	fun `no chrome above the header leaves the block height alone`() {
		assertEquals(219f, collapsedHeaderHeightPx(floor, 219f, 0, statusShown = true))
	}

	@Test
	fun `a tall status block does not size the header while another child is on show`() {
		// Keyboard up: the symbol input replaces the status block in the flipper. The three-line
		// status that grew the header to 270px is off screen, and the symbol input must not
		// inherit its rows.
		assertEquals(floor, collapsedHeaderHeightPx(floor, 222f, chrome, statusShown = false))
	}

	@Test
	fun `an unmeasured block keeps the floor whatever the chrome`() {
		assertEquals(floor, collapsedHeaderHeightPx(floor, 0f, chrome, statusShown = true))
		assertEquals(floor, collapsedHeaderHeightPx(floor, -1f, chrome, statusShown = true))
	}
}
