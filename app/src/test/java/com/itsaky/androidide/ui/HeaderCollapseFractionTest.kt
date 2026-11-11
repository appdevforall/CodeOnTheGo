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

class HeaderCollapseFractionTest {
	private val collapsedTop = 2000
	private val halfExpandedTop = 1200

	@Test
	fun `the header is whole with the sheet at its peek`() {
		assertEquals(0f, headerCollapseFraction(collapsedTop, collapsedTop, halfExpandedTop))
	}

	@Test
	fun `the header is gone when the sheet is half expanded`() {
		assertEquals(1f, headerCollapseFraction(halfExpandedTop, collapsedTop, halfExpandedTop))
	}

	@Test
	fun `the header stays gone above the half-expanded position`() {
		assertEquals(1f, headerCollapseFraction(100, collapsedTop, halfExpandedTop))
	}

	@Test
	fun `the header collapses in step with the rise to half expanded`() {
		assertEquals(0.25f, headerCollapseFraction(1800, collapsedTop, halfExpandedTop))
		assertEquals(0.5f, headerCollapseFraction(1600, collapsedTop, halfExpandedTop))
	}

	@Test
	fun `a sheet below its peek keeps the whole header`() {
		assertEquals(0f, headerCollapseFraction(2100, collapsedTop, halfExpandedTop))
	}

	@Test
	fun `a peek at or above the half-expanded position collapses the header on any rise`() {
		assertEquals(0f, headerCollapseFraction(1000, 1000, 1200))
		assertEquals(1f, headerCollapseFraction(999, 1000, 1200))
	}
}
