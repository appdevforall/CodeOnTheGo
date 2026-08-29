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

package com.itsaky.androidide.preferences

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Walks the real production tree, unlike
 * [com.itsaky.androidide.fragments.IDEPreferencesFragmentTest]'s `collectTooltipTags` coverage,
 * which only exercises synthetic fixtures. Duplicate keys are already guarded by
 * `collectTooltipTags` at runtime; this instead catches two different rows sharing the same
 * tooltipTag (a content bug, not a key collision) - the class of bug that shipped undetected
 * in XMLPreferencesScreen/XMLFormattingOptions.
 */
class IDEPreferencesTooltipTagsTest {
	@Test
	fun `every non-blank tooltipTag in the real preference tree is unique`() {
		IDEPreferences.clearPreferences()
		IDEPreferences.addRootPreferences()

		val tags = mutableListOf<String>()

		fun visit(items: List<IPreference>) {
			for (item in items) {
				if (item.tooltipTag.isNotBlank()) {
					tags.add(item.tooltipTag)
				}
				if (item is IPreferenceGroup) {
					visit(item.children)
				}
			}
		}

		visit(IDEPreferences.children)

		val duplicates = tags.groupingBy { it }.eachCount().filterValues { it > 1 }
		assertThat(duplicates).isEmpty()
	}
}
