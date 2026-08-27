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

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule

/**
 * The save counter is paired with `areFilesSaving` here, in the retained ViewModel, rather than
 * in the editor activity: a save running under `NonCancellable` outlives the activity instance
 * that started it, so a per-instance counter would let it clear the flag for a save the
 * recreated instance had already started.
 */
class EditorViewModelSaveFlagTest {
	@get:Rule
	var rule: TestRule = InstantTaskExecutorRule()

	private lateinit var viewModel: EditorViewModel

	@Before
	fun setUp() {
		viewModel = EditorViewModel()
	}

	@Test
	fun givenOverlappingSaves_whenTheFirstFinishes_thenTheFlagStaysRaisedUntilTheLastDoes() {
		viewModel.beginFileSave()
		viewModel.beginFileSave()
		assertThat(viewModel.areFilesSaving).isTrue()

		viewModel.endFileSave()
		assertThat(viewModel.areFilesSaving).isTrue()

		viewModel.endFileSave()
		assertThat(viewModel.areFilesSaving).isFalse()
	}

	@Test
	fun givenAnUnbalancedEnd_whenTheNextSaveRuns_thenTheFlagStillTracksIt() {
		// Floored at zero. Left negative, the "reached zero" test never matches again and
		// SaveFileAction stays disabled for the life of this ViewModel.
		viewModel.endFileSave()
		assertThat(viewModel.areFilesSaving).isFalse()

		viewModel.beginFileSave()
		assertThat(viewModel.areFilesSaving).isTrue()

		viewModel.endFileSave()
		assertThat(viewModel.areFilesSaving).isFalse()
	}
}
