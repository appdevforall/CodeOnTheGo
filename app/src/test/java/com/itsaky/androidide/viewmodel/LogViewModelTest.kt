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

import com.itsaky.androidide.models.LogFilter
import com.itsaky.androidide.models.LogLine
import com.itsaky.androidide.utils.ILogger
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the snapshot-then-tail pipeline in [LogViewModel]. Uses [runBlocking] with real
 * dispatchers because [LogViewModel.uiEvents] runs on [kotlinx.coroutines.Dispatchers.Default]
 * and chunks output on a real 50ms cadence.
 */
class LogViewModelTest {

	private class TestLogViewModel : LogViewModel()

	companion object {
		private const val EVENT_TIMEOUT_MS = 5000L
	}

	/** Collects [LogViewModel.uiEvents] into a channel inside [block]'s scope. */
	private fun withCollectedEvents(
		viewModel: LogViewModel,
		block: suspend (events: Channel<LogViewModel.UiEvent>) -> Unit,
	) = runBlocking {
		coroutineScope {
			val events = Channel<LogViewModel.UiEvent>(Channel.UNLIMITED)
			launch { viewModel.uiEvents.collect { events.send(it) } }
			try {
				withTimeout(EVENT_TIMEOUT_MS) {
					block(events)
				}
			} finally {
				coroutineContext.cancelChildren()
			}
		}
	}

	@Test
	fun `collection starts with a snapshot of submitted lines`() {
		val viewModel = TestLogViewModel()
		viewModel.submit(ILogger.Level.ERROR, "first")
		viewModel.submit(ILogger.Level.DEBUG, "second")

		withCollectedEvents(viewModel) { events ->
			val snapshot = events.receive()
			assertTrue(snapshot is LogViewModel.UiEvent.SetText)
			assertEquals("first\nsecond\n", (snapshot as LogViewModel.UiEvent.SetText).text)
		}
	}

	@Test
	fun `live lines arrive as appends after the snapshot with no gaps or duplicates`() {
		val viewModel = TestLogViewModel()
		viewModel.submit(null, "old")

		withCollectedEvents(viewModel) { events ->
			assertEquals("old\n", (events.receive() as LogViewModel.UiEvent.SetText).text)

			repeat(5) { viewModel.submit(null, "live$it") }

			val appended = StringBuilder()
			while (!appended.endsWith("live4\n")) {
				val event = events.receive()
				assertTrue(event is LogViewModel.UiEvent.Append)
				appended.append((event as LogViewModel.UiEvent.Append).text)
			}
			assertEquals("live0\nlive1\nlive2\nlive3\nlive4\n", appended.toString())
		}
	}

	@Test
	fun `changing the filter re-renders history with only matching lines`() {
		val viewModel = TestLogViewModel()
		viewModel.submit(ILogger.Level.ERROR, "error line")
		viewModel.submit(ILogger.Level.DEBUG, "debug line")
		viewModel.setFilter(LogFilter(enabledLevels = setOf(ILogger.Level.ERROR)))

		withCollectedEvents(viewModel) { events ->
			assertEquals("error line\n", (events.receive() as LogViewModel.UiEvent.SetText).text)
		}
	}

	@Test
	fun `live lines failing the filter are not appended`() {
		val viewModel = TestLogViewModel()
		viewModel.setFilter(LogFilter(enabledLevels = setOf(ILogger.Level.ERROR)))

		withCollectedEvents(viewModel) { events ->
			assertEquals("", (events.receive() as LogViewModel.UiEvent.SetText).text)

			viewModel.submit(ILogger.Level.DEBUG, "hidden")
			viewModel.submit(ILogger.Level.ERROR, "visible")

			val append = events.receive()
			assertTrue(append is LogViewModel.UiEvent.Append)
			assertEquals("visible\n", (append as LogViewModel.UiEvent.Append).text)
		}
	}

	@Test
	fun `filter change while collecting emits a new snapshot`() {
		val viewModel = TestLogViewModel()
		viewModel.submit(ILogger.Level.ERROR, "error line")
		viewModel.submit(ILogger.Level.DEBUG, "debug line")

		withCollectedEvents(viewModel) { events ->
			assertEquals(
				"error line\ndebug line\n",
				(events.receive() as LogViewModel.UiEvent.SetText).text,
			)

			viewModel.setFilter(LogFilter(text = "debug"))

			var event = events.receive()
			// Skip any in-flight appends from the previous generation
			while (event !is LogViewModel.UiEvent.SetText) {
				event = events.receive()
			}
			assertEquals("debug line\n", event.text)
		}
	}

	@Test
	fun `clear empties history and re-renders`() {
		val viewModel = TestLogViewModel()
		viewModel.submit(null, "stale")

		withCollectedEvents(viewModel) { events ->
			assertEquals("stale\n", (events.receive() as LogViewModel.UiEvent.SetText).text)

			viewModel.clear()

			var event = events.receive()
			while (event !is LogViewModel.UiEvent.SetText) {
				event = events.receive()
			}
			assertEquals("", event.text)
			assertEquals("", viewModel.snapshotUnfiltered())
		}
	}

	@Test
	fun `re-collection replays history as a snapshot without duplicates`() {
		val viewModel = TestLogViewModel()
		viewModel.submit(null, "line")

		// First collection (e.g. before a configuration change)
		withCollectedEvents(viewModel) { events ->
			assertEquals("line\n", (events.receive() as LogViewModel.UiEvent.SetText).text)
		}

		// Second collection sees the same content once, via the snapshot
		withCollectedEvents(viewModel) { events ->
			assertEquals("line\n", (events.receive() as LogViewModel.UiEvent.SetText).text)
		}
	}

	@Test
	fun `LogLine level and text are captured before the instance is recycled`() {
		val viewModel = TestLogViewModel()
		val line = LogLine.obtain(ILogger.Level.ERROR, "MyTag", "kaboom")
		val expected = line.toSimpleString()

		viewModel.submit(line, simpleFormattingEnabled = true)
		// The pooled instance was recycled inside submit(); the retained entry must not change
		viewModel.setFilter(LogFilter(enabledLevels = setOf(ILogger.Level.ERROR)))

		assertEquals("$expected\n", viewModel.snapshotUnfiltered())
		withCollectedEvents(viewModel) { events ->
			assertEquals("$expected\n", (events.receive() as LogViewModel.UiEvent.SetText).text)
		}
	}
}
