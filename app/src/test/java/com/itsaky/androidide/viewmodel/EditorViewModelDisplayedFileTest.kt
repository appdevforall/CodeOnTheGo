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
 * [EditorViewModel.displayedFile] each time the displayed index changes; reading the current file
 * there named the tab being left, because the index is set before the current file.
 */
class EditorViewModelDisplayedFileTest {
	@get:Rule
	val rule: TestRule = InstantTaskExecutorRule()

	private val a = File("/project/A.kt")
	private val b = File("/project/B.kt")
	private lateinit var viewModel: EditorViewModel
	private val heard = mutableListOf<File?>()

	@Before
	fun setUp() {
		viewModel = EditorViewModel()
		// The activity's observer, reduced to what it reports.
		viewModel._displayedFile.observeForever { heard += viewModel.displayedFile() }
		heard.clear()
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

		// The order of BaseEditorActivity.onTabSelected: the index first, then setCurrentFile.
		viewModel.displayedFileIndex = 0
		viewModel.setCurrentFile(0, a)

		assertThat(heard).containsExactly(a, a)
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
}
