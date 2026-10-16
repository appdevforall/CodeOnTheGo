package com.itsaky.androidide.api

import androidx.annotation.VisibleForTesting
import com.itsaky.androidide.logs.LogBuffer
import com.itsaky.androidide.models.LogFilter
import com.itsaky.androidide.plugins.services.LogEntry
import com.itsaky.androidide.plugins.services.LogLevel
import com.itsaky.androidide.plugins.services.LogQuery
import com.itsaky.androidide.plugins.services.LogReadResult
import com.itsaky.androidide.plugins.services.LogSource
import com.itsaky.androidide.utils.ILogger
import com.itsaky.androidide.viewmodel.LogViewModel
import java.lang.ref.WeakReference

/**
 * Serves the App Logs and IDE Logs tabs' retained history to plugins
 * ([com.itsaky.androidide.plugins.services.IdeLogService]). The history lives in the editor
 * activity's view models, so the activity attaches them here; with none attached, reads are empty.
 */
object LogsProvider {
	// Written on the main thread, read on whichever thread a plugin calls in on.
	@Volatile
	private var appLogsRef: WeakReference<LogViewModel>? = null

	@Volatile
	private var ideLogsRef: WeakReference<LogViewModel>? = null

	fun attach(
		appLogs: LogViewModel,
		ideLogs: LogViewModel,
	) {
		synchronized(this) {
			appLogsRef = WeakReference(appLogs)
			ideLogsRef = WeakReference(ideLogs)
		}
	}

	/**
	 * Detaches [appLogs] and [ideLogs] if they are still the attached pair. A no-op otherwise, so
	 * one editor instance's teardown cannot wipe out the view models a live sibling attached.
	 */
	fun detach(
		appLogs: LogViewModel,
		ideLogs: LogViewModel,
	) {
		synchronized(this) {
			if (appLogsRef?.get() === appLogs) appLogsRef = null
			if (ideLogsRef?.get() === ideLogs) ideLogsRef = null
		}
	}

	fun read(
		source: LogSource,
		query: LogQuery,
	): LogReadResult {
		val ref =
			when (source) {
				LogSource.APP -> appLogsRef
				LogSource.IDE -> ideLogsRef
			}
		val viewModel = ref?.get() ?: return LogReadResult.EMPTY
		val (entries, truncated) = viewModel.tailFiltered(query.toLogFilter(), query.effectiveMaxLines)
		return boundToChars(entries, truncated, LogQuery.MAX_CHARS)
	}

	/**
	 * Drops the oldest entries until the texts fit in [maxChars]. A single entry larger than that
	 * is cut to its first [maxChars] characters: the head of a stack trace is the part that names
	 * the exception.
	 */
	@VisibleForTesting
	internal fun boundToChars(
		entries: List<LogBuffer.Entry>,
		truncated: Boolean,
		maxChars: Int,
	): LogReadResult {
		require(maxChars > 0) { "maxChars must be positive" }
		if (entries.isEmpty()) return LogReadResult.EMPTY
		var start = entries.size
		var total = 0
		while (start > 0) {
			val length = contentLength(entries[start - 1].text)
			if (total + length > maxChars) break
			total += length
			start--
		}
		if (start == entries.size) {
			val newest = entries.last()
			val text = newest.text.stripLineEnd()
			// Back off one char rather than leave a lone high surrogate at the cut.
			val end = if (Character.isHighSurrogate(text[maxChars - 1])) maxChars - 1 else maxChars
			return LogReadResult(
				listOf(LogEntry(newest.level?.toPluginLevel(), text.substring(0, end))),
				truncated = true,
			)
		}
		val kept = entries.subList(start, entries.size).map { LogEntry(it.level?.toPluginLevel(), it.text.stripLineEnd()) }
		return LogReadResult(kept, truncated = truncated || start > 0)
	}

	/** Length of [text] without its line terminator, LF or CRLF, and without allocating. */
	private fun contentLength(text: String): Int {
		var end = text.length
		while (end > 0 && (text[end - 1] == '\n' || text[end - 1] == '\r')) end--
		return end
	}

	private fun String.stripLineEnd(): String = substring(0, contentLength(this))

	private fun LogQuery.toLogFilter(): LogFilter =
		LogFilter(
			enabledLevels =
				if (levels.isEmpty()) {
					LogFilter.ALL_LEVELS
				} else {
					levels.mapTo(mutableSetOf()) { it.toILoggerLevel() }
				},
			text = text.trim(),
		)

	private fun LogLevel.toILoggerLevel(): ILogger.Level =
		when (this) {
			LogLevel.VERBOSE -> ILogger.Level.VERBOSE
			LogLevel.DEBUG -> ILogger.Level.DEBUG
			LogLevel.INFO -> ILogger.Level.INFO
			LogLevel.WARNING -> ILogger.Level.WARNING
			LogLevel.ERROR -> ILogger.Level.ERROR
		}

	private fun ILogger.Level.toPluginLevel(): LogLevel =
		when (this) {
			ILogger.Level.VERBOSE -> LogLevel.VERBOSE
			ILogger.Level.DEBUG -> LogLevel.DEBUG
			ILogger.Level.INFO -> LogLevel.INFO
			ILogger.Level.WARNING -> LogLevel.WARNING
			ILogger.Level.ERROR -> LogLevel.ERROR
		}
}
