package com.itsaky.androidide.viewmodel

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import java.io.File

/**
 * What plugins hear as the active file (ADFA-7141). EditorHandlerActivity reports
 * [EditorViewModel.displayedFile] each time the displayed index changes, and plugins may read the
 * current file in that callback; both used to name the tab being left.
 */
class EditorViewModelDisplayedFileTest {
	@get:Rule
	val rule: TestRule = InstantTaskExecutorRule()

	private val a = File("/project/A.kt")
	private val b = File("/project/B.kt")
	private val s = File("/project/S.kt")
	private val t = File("/project/T.kt")
	private lateinit var viewModel: EditorViewModel
	private val heard = mutableListOf<File?>()

	// What a plugin reads with IdeEditorService.getCurrentFile() inside its callback.
	private val currentInCallback = mutableListOf<File?>()

	@Before
	fun setUp() {
		viewModel = EditorViewModel()
		// The activity's observer, reduced to what it reports.
		viewModel._displayedFile.observeForever {
			heard += viewModel.displayedFile()
			currentInCallback += viewModel.getCurrentFile()
		}
		heard.clear()
		currentInCallback.clear()
	}

	private fun open(file: File) {
		viewModel.addFile(file)
		viewModel.setCurrentFile(viewModel.getOpenedFileCount() - 1, file)
	}

	@Test
	fun openingAFileReportsThatFile() {
		open(a)
		open(b)

		assertThat(heard).containsExactly(a, b).inOrder()
	}

	@Test
	fun switchingTabsReportsTheTabSwitchedTo() {
		open(a)
		open(b)
		heard.clear()

		currentInCallback.clear()

		// What BaseEditorActivity.onTabSelected does: select the index, then again with the editor's file.
		viewModel.selectFile(0)
		viewModel.selectFile(0, a)

		assertThat(heard).containsExactly(a, a)
		assertThat(currentInCallback).containsExactly(a, a)
	}

	@Test
	fun aTabWhoseEditorHasNotLoadedKeepsTheOpenedFileCurrent() {
		open(a)
		open(b)

		// A new editor knows its file only once its text has loaded.
		viewModel.selectFile(0)
		viewModel.selectFile(0, editorFile = null)

		assertThat(viewModel.getCurrentFile()).isEqualTo(a)
	}

	@Test
	fun aListenerReadingTheCurrentFileGetsTheShownFile() {
		open(a)
		open(b)
		currentInCallback.clear()

		viewModel.setCurrentFile(0, a)

		assertThat(currentInCallback).containsExactly(a)
	}

	@Test
	fun aPluginTabReportsNoFile() {
		open(a)
		heard.clear()

		viewModel.displayedFileIndex = -1

		assertThat(heard).containsExactly(null)
	}

	@Test
	fun closingTheLastFileReportsNoFile() {
		open(a)
		heard.clear()

		viewModel.removeFile(0)

		assertThat(heard).containsExactly(null)
		assertThat(viewModel.getCurrentFile()).isNull()
	}

	@Test
	fun closingAllFilesReportsNoFile() {
		open(a)
		open(b)
		heard.clear()

		viewModel.removeAllFiles()

		assertThat(heard).containsExactly(null)
	}

	@Test
	fun closingEarlierTabsKeepsTheShownFileCurrent() {
		open(a)
		open(b)
		open(s)

		viewModel.removeFile(0)
		viewModel.removeFile(0)

		assertThat(viewModel.displayedFile()).isEqualTo(s)
		assertThat(viewModel.displayedFileIndex).isEqualTo(0)
		assertThat(viewModel.getCurrentFileIndex()).isEqualTo(0)
		assertThat(viewModel.getCurrentFile()).isEqualTo(s)
	}

	@Test
	fun closingAnEarlierTabDoesNotShowTheNextFile() {
		open(a)
		open(b)
		open(s)
		open(t)
		viewModel.setCurrentFile(2, s)

		viewModel.removeFile(0)

		assertThat(viewModel.displayedFile()).isEqualTo(s)
		assertThat(viewModel.displayedFileIndex).isEqualTo(1)
		assertThat(viewModel.getCurrentFileIndex()).isEqualTo(1)
	}

	@Test
	fun closingALaterTabLeavesTheIndexesAlone() {
		open(a)
		open(s)
		open(b)
		viewModel.setCurrentFile(1, s)

		viewModel.removeFile(2)

		assertThat(viewModel.displayedFile()).isEqualTo(s)
		assertThat(viewModel.displayedFileIndex).isEqualTo(1)
		assertThat(viewModel.getCurrentFileIndex()).isEqualTo(1)
	}

	// The activity's observer maps the shown index to a tab and a view, so the index must not move
	// while the closed tab's view still exists.
	@Test
	fun theShownIndexChangesOnlyAfterTheClosedTabsViewIsGone() {
		open(a)
		open(b)
		open(s)
		open(t)
		viewModel.setCurrentFile(2, s)
		var viewsRemoved = false
		val removedAtEachFiring = mutableListOf<Boolean>()
		viewModel._displayedFile.observeForever { removedAtEachFiring += viewsRemoved }
		removedAtEachFiring.clear()

		viewModel.removeFile(0) { viewsRemoved = true }

		assertThat(removedAtEachFiring).isNotEmpty()
		assertThat(removedAtEachFiring).doesNotContain(false)
		assertThat(viewModel.displayedFile()).isEqualTo(s)
	}
}
