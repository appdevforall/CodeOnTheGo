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

package com.itsaky.androidide.fragments.output

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.viewmodel.BuildOutputViewModel
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BuildOutputBufferTest {
	private fun BuildOutputBuffer.offer(text: String) {
		offer(text, sessionToken = 0)
	}

	@Test
	fun `output below limits is emitted in order with one trailing newline`() =
		runTest {
			val buffer = BuildOutputBuffer(maxPendingChars = 64, maxBatchChars = 64)

			buffer.offer("first")
			buffer.offer("second\n")

			assertThat(buffer.takeBatch().text).isEqualTo("first\nsecond\n")
		}

	@Test
	fun `output is split into bounded batches without reordering`() =
		runTest {
			val buffer = BuildOutputBuffer(maxPendingChars = 64, maxBatchChars = 6)

			buffer.offer("aa")
			buffer.offer("bb")
			buffer.offer("cc")

			val first = buffer.takeBatch().text
			val second = buffer.takeBatch().text
			assertThat(first).isEqualTo("aa\nbb\n")
			assertThat(second).isEqualTo("cc\n")
			assertThat(first.length).isAtMost(6)
			assertThat(second.length).isAtMost(6)
		}

	@Test
	fun `one indivisible input may exceed the batch limit`() =
		runTest {
			val buffer = BuildOutputBuffer(maxPendingChars = 64, maxBatchChars = 4)

			buffer.offer("oversized")

			assertThat(buffer.takeBatch().text).isEqualTo("oversized\n")
		}

	@Test
	fun `overflow evicts oldest output and keeps newest output`() =
		runTest {
			val buffer = BuildOutputBuffer(maxPendingChars = 11, maxBatchChars = 128)

			buffer.offer("one")
			buffer.offer("two")
			buffer.offer("three")
			buffer.offer("four")

			val batch = buffer.takeBatch()
			assertThat(batch.text).isEqualTo("[2 build output lines omitted]\nthree\nfour\n")
			assertThat(batch.sourceChars).isEqualTo(19)
			assertThat(buffer.pendingChars).isAtMost(11)
		}

	@Test
	fun `oversized input retains its newest bounded tail`() =
		runTest {
			val buffer = BuildOutputBuffer(maxPendingChars = 8, maxBatchChars = 128)

			buffer.offer("0123456789")

			val batch = buffer.takeBatch()
			assertThat(batch.text).isEqualTo("[1 build output line omitted]\n3456789\n")
			assertThat(batch.sourceChars).isEqualTo(11)
			assertThat(buffer.pendingChars).isEqualTo(0)
		}

	@Test
	fun `clear resets pending output and overflow accounting`() =
		runTest {
			val buffer = BuildOutputBuffer(maxPendingChars = 4, maxBatchChars = 64)

			buffer.offer("kept")
			buffer.offer("dropped")
			buffer.clear()
			buffer.offer("new")

			assertThat(buffer.takeBatch().text).isEqualTo("new\n")
		}

	@Test
	fun `in-flight batch keeps the session token from its producer`() =
		runTest {
			val buffer = BuildOutputBuffer(maxPendingChars = 64, maxBatchChars = 64)

			buffer.offer("old", sessionToken = 3)
			val inFlight = buffer.takeBatch()
			buffer.clear()
			buffer.offer("new", sessionToken = 4)

			assertThat(inFlight.sessionToken).isEqualTo(3)
			assertThat(buffer.takeBatch().sessionToken).isEqualTo(4)
		}

	@Test
	fun `clear invalidates stale view model session tokens`() =
		runTest {
			val viewModel =
				BuildOutputViewModel(ApplicationProvider.getApplicationContext<Application>())
			viewModel.clear()
			val staleToken = viewModel.currentSessionToken

			assertThat(viewModel.append("old\n", staleToken)).isTrue()
			viewModel.clear()

			assertThat(viewModel.append("stale\n", staleToken)).isFalse()
			assertThat(viewModel.getFullContent()).isEmpty()
		}

	@Test
	fun `window refresh uses hysteresis after reaching the editor limit`() {
		val batchChars = 32 * 1024
		var sourceChars = BuildOutputViewModel.EDITOR_WINDOW_MAX_CHARS
		var refreshCount = 0

		repeat(6) {
			if (BuildOutputViewModel.wouldExceedEditorWindow(sourceChars, batchChars)) {
				refreshCount++
				sourceChars =
					BuildOutputViewModel.editorSourceCharsAfterRefresh(
						BuildOutputViewModel.EDITOR_WINDOW_MAX_CHARS,
					)
			} else {
				sourceChars += batchChars
			}
		}

		assertThat(refreshCount).isEqualTo(2)
	}

	@Test
	fun `filtered editor refreshes when hidden source output advances the window`() {
		val oldMatch = "old match\n"
		val hidden = "x".repeat(BuildOutputViewModel.EDITOR_WINDOW_MAX_CHARS)
		var session = oldMatch
		var sourceChars = session.length
		var visible = oldMatch

		session += hidden
		if (BuildOutputViewModel.wouldExceedEditorWindow(sourceChars, hidden.length)) {
			val window = session.takeLast(BuildOutputViewModel.EDITOR_WINDOW_MAX_CHARS)
			visible =
				BuildOutputViewModel.filterLines(
					window,
					query = "match",
					showTimestamps = true,
					showDeltas = true,
				)
			sourceChars = BuildOutputViewModel.editorSourceCharsAfterRefresh(window.length)
		}

		assertThat(visible).isEmpty()
		assertThat(sourceChars).isLessThan(BuildOutputViewModel.EDITOR_WINDOW_MAX_CHARS)
	}

	@Test
	fun `refreshed tail applies filtering and timing visibility`() {
		val prefix = BuildOutputViewModel.formatLinePrefix(1_722_000_000_000L, 42L)
		val tail = prefix + "ignored\n" + prefix + "newest output\n"

		val visible =
			BuildOutputViewModel.filterLines(
				tail,
				query = "newest",
				showTimestamps = false,
				showDeltas = false,
			)

		assertThat(visible).isEqualTo("newest output\n")
	}
}
