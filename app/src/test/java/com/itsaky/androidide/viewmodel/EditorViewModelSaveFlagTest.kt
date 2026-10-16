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

	@Test
	fun givenNoSaveInFlight_whenAnActionIsDeferred_thenItRunsNow() {
		var runs = 0
		viewModel.whenNoSaves { runs++ }
		assertThat(runs).isEqualTo(1)
	}

	@Test
	fun givenOverlappingSaves_whenAnActionIsDeferred_thenItRunsOnceAfterTheLastFinishes() {
		// A recreate defers releasing the old editors this way; releasing earlier would null the
		// file an in-flight save is about to read.
		var runs = 0
		viewModel.beginFileSave()
		viewModel.beginFileSave()
		viewModel.whenNoSaves { runs++ }

		viewModel.endFileSave()
		assertThat(runs).isEqualTo(0)

		viewModel.endFileSave()
		assertThat(runs).isEqualTo(1)

		viewModel.beginFileSave()
		viewModel.endFileSave()
		assertThat(runs).isEqualTo(1)
	}
}
