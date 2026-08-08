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

import com.itsaky.androidide.viewmodel.BuildOutputViewModel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildOutputBufferTest {
	private fun BuildOutputBuffer.offer(text: String) {
		offer(text, sessionGeneration = 0)
	}

	@Test
	fun `output below limits is emitted in order with one trailing newline`() =
		runTest {
			val buffer = BuildOutputBuffer(maxPendingChars = 64, maxBatchChars = 64)

			buffer.offer("first")
			buffer.offer("second\n")

			assertEquals("first\nsecond\n", buffer.takeBatch().text)
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
			assertEquals("aa\nbb\n", first)
			assertEquals("cc\n", second)
			assertTrue(first.length <= 6)
			assertTrue(second.length <= 6)
		}

	@Test
	fun `one indivisible input may exceed the batch limit`() =
		runTest {
			val buffer = BuildOutputBuffer(maxPendingChars = 64, maxBatchChars = 4)

			buffer.offer("oversized")

			assertEquals("oversized\n", buffer.takeBatch().text)
		}

	@Test
	fun `overflow is coalesced before retained output resumes`() =
		runTest {
			val buffer = BuildOutputBuffer(maxPendingChars = 8, maxBatchChars = 128)

			buffer.offer("one")
			buffer.offer("two")
			buffer.offer("dropped one")
			buffer.offer("dropped two\nand three")

			assertEquals(
				"one\ntwo\n[3 build output lines omitted]\n",
				buffer.takeBatch().text,
			)
			assertTrue(buffer.pendingChars <= 8)
		}

	@Test
	fun `overflow after resumed output starts a new omission marker`() =
		runTest {
			val buffer = BuildOutputBuffer(maxPendingChars = 8, maxBatchChars = 4)

			buffer.offer("one")
			buffer.offer("two")
			buffer.offer("first dropped")
			assertEquals("one\n", buffer.takeBatch().text)

			buffer.offer("new")
			buffer.offer("second dropped")

			assertEquals("two\n", buffer.takeBatch().text)
			assertEquals("[1 build output lines omitted]\n", buffer.takeBatch().text)
			assertEquals("new\n", buffer.takeBatch().text)
			assertEquals("[1 build output lines omitted]\n", buffer.takeBatch().text)
		}

	@Test
	fun `clear resets pending output and overflow accounting`() =
		runTest {
			val buffer = BuildOutputBuffer(maxPendingChars = 4, maxBatchChars = 64)

			buffer.offer("kept")
			buffer.offer("dropped")
			buffer.clear()
			buffer.offer("new")

			assertEquals("new\n", buffer.takeBatch().text)
		}

	@Test
	fun `in-flight batch keeps the session generation from its producer`() =
		runTest {
			val buffer = BuildOutputBuffer(maxPendingChars = 64, maxBatchChars = 64)

			buffer.offer("old", sessionGeneration = 3)
			val inFlight = buffer.takeBatch()
			buffer.clear()
			buffer.offer("new", sessionGeneration = 4)

			assertEquals(3, inFlight.sessionGeneration)
			assertEquals(4, buffer.takeBatch().sessionGeneration)
		}

	@Test
	fun `repeated live batches refresh to the newest bounded editor tail`() {
		val chunk = "x".repeat(200 * 1024)
		val newest = "newest build output\n"
		var session = ""
		var visible = ""
		var refreshCount = 0

		for (batch in listOf(chunk, chunk, chunk + newest)) {
			session += batch
			visible =
				if (BuildOutputViewModel.wouldExceedEditorWindow(visible.length, batch.length)) {
					refreshCount++
					session.takeLast(BuildOutputViewModel.EDITOR_WINDOW_MAX_CHARS)
				} else {
					visible + batch
				}
		}

		assertEquals(1, refreshCount)
		assertTrue(visible.length <= BuildOutputViewModel.EDITOR_WINDOW_MAX_CHARS)
		assertTrue(visible.endsWith(newest))
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
			sourceChars = window.length
		}

		assertEquals("", visible)
		assertEquals(BuildOutputViewModel.EDITOR_WINDOW_MAX_CHARS, sourceChars)
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

		assertEquals("newest output\n", visible)
	}
}
