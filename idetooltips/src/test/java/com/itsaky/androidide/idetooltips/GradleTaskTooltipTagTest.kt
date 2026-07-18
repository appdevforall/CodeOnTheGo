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

package com.itsaky.androidide.idetooltips

import org.junit.Assert.assertEquals
import org.junit.Test

/** Verifies the Gradle task path -> tooltip tag mapping used by RunTasksListAdapter. */
class GradleTaskTooltipTagTest {
	@Test
	fun `project-qualified task path maps to colon tag`() {
		assertEquals("gradle.app:assembleDebug", TooltipTag.gradleTaskTooltipTag(":app:assembleDebug"))
	}

	@Test
	fun `root task path maps to dotted tag`() {
		assertEquals("gradle.clean", TooltipTag.gradleTaskTooltipTag(":clean"))
	}

	@Test
	fun `merge task path is preserved verbatim`() {
		assertEquals(
			"gradle.app:mergeReleaseResources",
			TooltipTag.gradleTaskTooltipTag(":app:mergeReleaseResources"),
		)
	}

	@Test
	fun `plugin-builder task maps to gradle-prefixed tag`() {
		assertEquals("gradle.assemblePlugin", TooltipTag.gradleTaskTooltipTag(":assemblePlugin"))
	}
}
