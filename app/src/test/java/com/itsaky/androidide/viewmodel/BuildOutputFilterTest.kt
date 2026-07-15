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

package com.itsaky.androidide.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class BuildOutputFilterTest {

	@Test
	fun `empty query returns content unchanged`() {
		val content = "> Task :app:compileDebugKotlin\nBUILD SUCCESSFUL\n"
		assertSame(content, BuildOutputViewModel.filterLines(content, ""))
	}

	@Test
	fun `only matching lines are kept, case-insensitively`() {
		val content = "> Task :app:compileDebugKotlin\nwarning: deprecated API\nBUILD SUCCESSFUL\n"
		assertEquals(
			"> Task :app:compileDebugKotlin\n",
			BuildOutputViewModel.filterLines(content, "task"),
		)
	}

	@Test
	fun `no matches yields empty text`() {
		val content = "BUILD SUCCESSFUL in 1s\n"
		assertEquals("", BuildOutputViewModel.filterLines(content, "error"))
	}

	@Test
	fun `multi-line batch keeps every matching line`() {
		val content = "> Task :a\nnoise\n> Task :b\nnoise\n"
		assertEquals(
			"> Task :a\n> Task :b\n",
			BuildOutputViewModel.filterLines(content, "> Task"),
		)
	}

	@Test
	fun `batch without trailing newline still terminates matched lines`() {
		val content = "> Task :a\n> Task :b"
		assertEquals(
			"> Task :a\n> Task :b\n",
			BuildOutputViewModel.filterLines(content, "> Task"),
		)
	}
}
