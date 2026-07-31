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

/**
 * Decides when a filtered re-render deserves a "no matches" flash: only on a fresh
 * transition into no-matches for a non-empty source, never on the initial render.
 */
class FilterNoMatchTracker {
	private var hasRendered = false
	private var wasLastFilteredResultEmpty = false

	fun reset() {
		hasRendered = false
		wasLastFilteredResultEmpty = false
	}

	/** Marks the initial render (e.g. window restore after rotation) without ever flashing. */
	fun prime(isFilteredEmpty: Boolean) {
		hasRendered = true
		wasLastFilteredResultEmpty = isFilteredEmpty
	}

	/** Records a full re-render; returns `true` when it newly transitioned to "no matches". */
	fun onRender(
		isSourceEmpty: Boolean,
		isFilteredEmpty: Boolean,
	): Boolean {
		if (!hasRendered) {
			prime(isFilteredEmpty)
			return false
		}
		if (!isSourceEmpty && isFilteredEmpty) {
			val shouldFlash = !wasLastFilteredResultEmpty
			wasLastFilteredResultEmpty = true
			return shouldFlash
		}
		wasLastFilteredResultEmpty = false
		return false
	}
}
