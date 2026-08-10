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

package com.itsaky.androidide.fragments.output

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterNoMatchTrackerTest {
	@Test
	fun `initial render never flashes even with no matches`() {
		val tracker = FilterNoMatchTracker()
		assertFalse(tracker.onRender(isSourceEmpty = false, isFilteredEmpty = true))
	}

	@Test
	fun `transition into no-matches flashes exactly once`() {
		val tracker = FilterNoMatchTracker()
		tracker.onRender(isSourceEmpty = false, isFilteredEmpty = false)

		assertTrue(tracker.onRender(isSourceEmpty = false, isFilteredEmpty = true))
		assertFalse(tracker.onRender(isSourceEmpty = false, isFilteredEmpty = true))
	}

	@Test
	fun `recovering matches re-arms the flash`() {
		val tracker = FilterNoMatchTracker()
		tracker.onRender(isSourceEmpty = false, isFilteredEmpty = false)
		assertTrue(tracker.onRender(isSourceEmpty = false, isFilteredEmpty = true))

		assertFalse(tracker.onRender(isSourceEmpty = false, isFilteredEmpty = false))
		assertTrue(tracker.onRender(isSourceEmpty = false, isFilteredEmpty = true))
	}

	@Test
	fun `empty source never flashes`() {
		val tracker = FilterNoMatchTracker()
		tracker.onRender(isSourceEmpty = false, isFilteredEmpty = false)
		assertFalse(tracker.onRender(isSourceEmpty = true, isFilteredEmpty = true))
	}

	@Test
	fun `prime suppresses the flash for a restored no-match render`() {
		val tracker = FilterNoMatchTracker()
		tracker.prime(isFilteredEmpty = true)
		// e.g. after rotation with an active no-match filter, the re-render must stay silent
		assertFalse(tracker.onRender(isSourceEmpty = false, isFilteredEmpty = true))
	}

	@Test
	fun `prime with matches still flashes on a later transition to no-matches`() {
		val tracker = FilterNoMatchTracker()
		tracker.prime(isFilteredEmpty = false)
		assertTrue(tracker.onRender(isSourceEmpty = false, isFilteredEmpty = true))
	}

	@Test
	fun `reset restores initial-render behavior`() {
		val tracker = FilterNoMatchTracker()
		tracker.onRender(isSourceEmpty = false, isFilteredEmpty = false)
		tracker.reset()

		assertFalse(tracker.onRender(isSourceEmpty = false, isFilteredEmpty = true))
	}
}
