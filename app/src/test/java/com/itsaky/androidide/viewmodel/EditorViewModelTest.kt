package com.itsaky.androidide.viewmodel

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import java.io.File

class EditorViewModelTest {

  private lateinit var viewModel: EditorViewModel

  @get:Rule
  var rule: TestRule = InstantTaskExecutorRule()

  @Before
  fun setUp() {
    viewModel = EditorViewModel()
  }

  @Test
  fun setCurrentFileTest() {
    assertEquals(viewModel.getCurrentFileIndex(), -1)
    assertEquals(viewModel.displayedFileIndex, -1)
    inner(0, File(""))
    inner(1, File(""))
    inner(2, File(""))
    inner(-1, File(""))
  }

  private fun inner(index: Int, file: File) {
    viewModel.setCurrentFile(index, file)
    assertEquals(viewModel.getCurrentFileIndex(), index)
    assertEquals(viewModel.displayedFileIndex, index)
  }

}