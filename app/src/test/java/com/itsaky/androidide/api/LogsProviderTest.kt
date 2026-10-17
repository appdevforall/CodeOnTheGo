package com.itsaky.androidide.api

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.logs.LogBuffer
import com.itsaky.androidide.plugins.services.LogEntry
import com.itsaky.androidide.plugins.services.LogLevel
import com.itsaky.androidide.plugins.services.LogQuery
import com.itsaky.androidide.plugins.services.LogReadResult
import com.itsaky.androidide.plugins.services.LogSource
import com.itsaky.androidide.utils.ILogger
import com.itsaky.androidide.viewmodel.AppLogsViewModel
import org.junit.After
import org.junit.Test

class LogsProviderTest {
	private val appLogs = AppLogsViewModel()
	private val ideLogs = AppLogsViewModel()

	@After
	fun tearDown() {
		LogsProvider.detach(appLogs, ideLogs)
	}

	@Test
	fun `read with nothing attached is empty`() {
		assertThat(LogsProvider.read(LogSource.APP, LogQuery())).isEqualTo(LogReadResult.EMPTY)
		assertThat(LogsProvider.read(LogSource.IDE, LogQuery())).isEqualTo(LogReadResult.EMPTY)
	}

	@Test
	fun `read serves each source from its own view model`() {
		appLogs.submit(ILogger.Level.INFO, "app line")
		ideLogs.submit(ILogger.Level.INFO, "ide line")
		LogsProvider.attach(appLogs, ideLogs)

		assertThat(LogsProvider.read(LogSource.APP, LogQuery()).entries)
			.containsExactly(LogEntry(LogLevel.INFO, "app line"))
		assertThat(LogsProvider.read(LogSource.IDE, LogQuery()).entries)
			.containsExactly(LogEntry(LogLevel.INFO, "ide line"))
	}

	@Test
	fun `read applies level, text and line-count filters`() {
		appLogs.submit(ILogger.Level.ERROR, "E MyTag: first crash")
		appLogs.submit(ILogger.Level.DEBUG, "D MyTag: noise")
		appLogs.submit(ILogger.Level.ERROR, "E Other: unrelated")
		appLogs.submit(ILogger.Level.ERROR, "E MyTag: second crash")
		appLogs.submit(ILogger.Level.ERROR, "E MyTag: third crash")
		LogsProvider.attach(appLogs, ideLogs)

		val result =
			LogsProvider.read(
				LogSource.APP,
				LogQuery(levels = setOf(LogLevel.ERROR), text = "  mytag ", maxLines = 2),
			)
		assertThat(result.entries.map { it.text })
			.containsExactly("E MyTag: second crash", "E MyTag: third crash")
			.inOrder()
		assertThat(result.truncated).isTrue()
	}

	@Test
	fun `a line with no level passes a level filter`() {
		appLogs.submit("continuation")
		appLogs.submit(ILogger.Level.DEBUG, "debug")
		LogsProvider.attach(appLogs, ideLogs)

		val result = LogsProvider.read(LogSource.APP, LogQuery(levels = setOf(LogLevel.ERROR)))
		assertThat(result.entries).containsExactly(LogEntry(null, "continuation"))
	}

	@Test
	fun `max lines is clamped rather than rejected`() {
		appLogs.submit(ILogger.Level.INFO, "a")
		appLogs.submit(ILogger.Level.INFO, "b")
		LogsProvider.attach(appLogs, ideLogs)

		val result = LogsProvider.read(LogSource.APP, LogQuery(maxLines = 0))
		assertThat(result.entries.map { it.text }).containsExactly("b")
		assertThat(result.truncated).isTrue()
	}

	@Test
	fun `detach leaves a different attached pair alone`() {
		appLogs.submit(ILogger.Level.INFO, "live")
		LogsProvider.attach(appLogs, ideLogs)

		LogsProvider.detach(AppLogsViewModel(), AppLogsViewModel())

		assertThat(LogsProvider.read(LogSource.APP, LogQuery()).entries).hasSize(1)
	}

	@Test
	fun `detach of the attached pair empties reads`() {
		appLogs.submit(ILogger.Level.INFO, "gone")
		LogsProvider.attach(appLogs, ideLogs)

		LogsProvider.detach(appLogs, ideLogs)

		assertThat(LogsProvider.read(LogSource.APP, LogQuery())).isEqualTo(LogReadResult.EMPTY)
	}

	@Test
	fun `char bound drops the oldest entries first`() {
		val entries = listOf(entry("aaaa"), entry("bbbb"), entry("cccc"))

		val result = LogsProvider.boundToChars(entries, truncated = false, maxChars = 9)

		assertThat(result.entries.map { it.text }).containsExactly("bbbb", "cccc").inOrder()
		assertThat(result.truncated).isTrue()
	}

	@Test
	fun `char bound keeps everything that fits`() {
		val entries = listOf(entry("aaaa"), entry("bbbb"))

		val result = LogsProvider.boundToChars(entries, truncated = false, maxChars = 8)

		assertThat(result.entries).hasSize(2)
		assertThat(result.truncated).isFalse()
	}

	@Test
	fun `char bound cuts a single oversized entry to its head`() {
		val result = LogsProvider.boundToChars(listOf(entry("abcdef")), truncated = false, maxChars = 3)

		assertThat(result.entries.single().text).isEqualTo("abc")
		assertThat(result.truncated).isTrue()
	}

	@Test
	fun `char bound does not split a surrogate pair`() {
		val result = LogsProvider.boundToChars(listOf(entry("ab\uD83D\uDE00c")), truncated = false, maxChars = 3)

		assertThat(result.entries.single().text).isEqualTo("ab")
		assertThat(result.truncated).isTrue()
	}

	@Test
	fun `char bound strips CRLF as well as LF and counts neither`() {
		val entries = listOf(LogBuffer.Entry(++seq, ILogger.Level.INFO, "aaaa\r\n"), entry("bbbb"))

		val result = LogsProvider.boundToChars(entries, truncated = false, maxChars = 8)

		assertThat(result.entries.map { it.text }).containsExactly("aaaa", "bbbb").inOrder()
		assertThat(result.truncated).isFalse()
	}

	@Test(expected = IllegalArgumentException::class)
	fun `char bound rejects a non-positive cap`() {
		LogsProvider.boundToChars(listOf(entry("a")), truncated = false, maxChars = 0)
	}

	private var seq = 0L

	private fun entry(text: String) = LogBuffer.Entry(++seq, ILogger.Level.INFO, "$text\n")
}
