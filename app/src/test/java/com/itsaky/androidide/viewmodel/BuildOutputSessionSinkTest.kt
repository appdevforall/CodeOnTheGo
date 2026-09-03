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

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Covers the write path that no longer depends on the Build Output tab existing.
 *
 * `BuildOutputFragment` used to be the only caller of [BuildOutputViewModel.append], and the pager
 * destroys that fragment whenever another tab is shown. A build started from the AI agent's chat
 * tab therefore wrote nothing to the session file, and `read_build_output` returned an empty log
 * for a build that had just failed with compiler errors.
 *
 * Mutation-mindset: revert [BuildOutputViewModel.appendAsync] to a no-op, or take the queue-drop
 * out of [BuildOutputViewModel.clear], and one of these goes red.
 */
@RunWith(RobolectricTestRunner::class)
class BuildOutputSessionSinkTest {
	private lateinit var viewModel: BuildOutputViewModel
	private lateinit var sessionFile: File

	@Before
	fun setUp() {
		val application = ApplicationProvider.getApplicationContext<Application>()
		sessionFile = File(application.cacheDir, BuildOutputViewModel.SESSION_FILE_NAME)
		sessionFile.delete()
		viewModel = BuildOutputViewModel(application)
	}

	@After
	fun tearDown() {
		sessionFile.delete()
	}

	/** Gives the view model's writer coroutine a chance to drain what was queued. */
	private fun awaitWrite(expected: String) {
		runBlocking {
			repeat(TRIES) {
				if (sessionFile.exists() && sessionFile.readText().contains(expected)) return@runBlocking
				Thread.sleep(WAIT_MS)
			}
		}
	}

	@Test
	fun `givenNoFragment_whenOutputIsAppended_thenTheSessionFileReceivesIt`() {
		viewModel.appendAsync("e: MainActivity.kt: (11, 23): Unresolved reference: Bundle")

		awaitWrite("Unresolved reference: Bundle")

		assertThat(sessionFile.readText()).contains("Unresolved reference: Bundle")
	}

	@Test
	fun `givenALineWithNoTrailingNewline_whenAppended_thenOneIsAdded`() {
		viewModel.appendAsync("> Task :app:compileDebugKotlin")

		awaitWrite("compileDebugKotlin")

		assertThat(sessionFile.readText()).isEqualTo("> Task :app:compileDebugKotlin\n")
	}

	@Test
	fun `givenSeveralLines_whenAppended_thenTheyKeepTheirOrder`() {
		viewModel.appendAsync("first\n")
		viewModel.appendAsync("second\n")
		viewModel.appendAsync("third\n")

		awaitWrite("third")

		assertThat(sessionFile.readText()).isEqualTo("first\nsecond\nthird\n")
	}

	@Test
	fun `givenOutputFromAFinishedBuild_whenTheSessionIsCleared_thenItDoesNotReachTheNewOne`() {
		viewModel.appendAsync("e: an error from the previous build\n")
		viewModel.clear()

		viewModel.appendAsync("> Task :app:compileDebugKotlin\n")
		awaitWrite("compileDebugKotlin")

		assertThat(sessionFile.readText()).doesNotContain("previous build")
	}

	@Test
	fun `givenWrittenOutput_whenTheSessionIsCleared_thenTheFileIsGone`() {
		viewModel.appendAsync("output\n")
		awaitWrite("output")

		viewModel.clear()

		assertThat(sessionFile.exists()).isFalse()
	}

	@Test
	fun `givenEmptyText_whenAppended_thenNothingIsWritten`() {
		viewModel.appendAsync("")
		Thread.sleep(WAIT_MS * 2)

		assertThat(sessionFile.exists()).isFalse()
	}

	private companion object {
		/** Polling budget for the writer coroutine: generous, and only paid on a failure. */
		const val TRIES = 100
		const val WAIT_MS = 20L
	}
}
