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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmptyStateFragmentViewModelTest {
	@Test
	fun `both states start empty`() {
		val viewModel = EmptyStateFragmentViewModel()
		assertTrue(viewModel.isEmpty.value)
		assertTrue(viewModel.isSourceEmpty.value)
	}

	@Test
	fun `setEmpty sets both states together`() {
		val viewModel = EmptyStateFragmentViewModel()

		viewModel.setEmpty(false)
		assertFalse(viewModel.isEmpty.value)
		assertFalse(viewModel.isSourceEmpty.value)

		viewModel.setEmpty(true)
		assertTrue(viewModel.isEmpty.value)
		assertTrue(viewModel.isSourceEmpty.value)
	}

	@Test
	fun `setEmptyState sets the states independently`() {
		val viewModel = EmptyStateFragmentViewModel()

		// Empty source with an active filter UI: content layout stays visible,
		// but content-dependent actions must still see an empty source.
		viewModel.setEmptyState(isEmpty = false, isSourceEmpty = true)
		assertFalse(viewModel.isEmpty.value)
		assertTrue(viewModel.isSourceEmpty.value)
	}
}
