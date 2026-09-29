package com.itsaky.androidide.plugins.services

/**
 * Read-only access to the IDE's App Logs and IDE Logs: the same retained history the editor's
 * bottom-sheet log tabs show. There is deliberately no way to clear or change a log here.
 *
 * Safe to call from any thread.
 */
interface IdeLogService {
	/**
	 * Returns the newest lines of [source] that match [query], oldest first.
	 *
	 * Never throws for a missing log: with no editor open, no app run yet, or the log sender
	 * disabled, the result is [LogReadResult.EMPTY].
	 */
	fun readLogs(
		source: LogSource,
		query: LogQuery,
	): LogReadResult
}

/** Which log tab to read. */
enum class LogSource {
	/** The App Logs tab: logcat output of the app under development. */
	APP,

	/** The IDE Logs tab: the IDE's own log. */
	IDE,
}

/** Severity of a log line. Mirrors the levels the log tabs' filter bar offers. */
enum class LogLevel {
	VERBOSE,
	DEBUG,
	INFO,
	WARNING,
	ERROR,
}

/**
 * Filter and bound for [IdeLogService.readLogs]. Matches the log tabs' filter bar: a line passes
 * when its level is in [levels] and it contains [text]. A line with no known level passes any
 * level filter, as it does in the tab.
 *
 * @property levels Levels to include. Empty means every level.
 * @property text Case-insensitive substring a line must contain; matches tags as well as
 *   messages, since both are part of the rendered line. Leading and trailing whitespace is
 *   ignored, as in the tab's filter bar; blank means no text filter.
 * @property maxLines The most lines to return, newest kept. Clamped to `1..`[MAX_LINES].
 */
data class LogQuery
	@JvmOverloads
	constructor(
		val levels: Set<LogLevel> = emptySet(),
		val text: String = "",
		val maxLines: Int = DEFAULT_MAX_LINES,
	) {
		/** [maxLines] clamped to the range the host honours. */
		val effectiveMaxLines: Int
			get() = maxLines.coerceIn(1, MAX_LINES)

		companion object {
			const val DEFAULT_MAX_LINES = 200
			const val MAX_LINES = 1000

			/**
			 * Upper bound on the UTF-16 chars of line content in one result (terminators not
			 * counted), whatever [maxLines] allows: a thousand stack-trace lines would otherwise
			 * be one very large string.
			 */
			const val MAX_CHARS = 128 * 1024
		}
	}

/**
 * One log line.
 *
 * @property level The line's severity, or null when the IDE does not know it.
 * @property text The line as the log tab renders it, without a trailing newline.
 */
data class LogEntry(
	val level: LogLevel?,
	val text: String,
)

/**
 * Result of [IdeLogService.readLogs].
 *
 * @property entries Matching lines, oldest first.
 * @property truncated True when more matching lines are retained than [entries] holds, because
 *   of [LogQuery.maxLines] or [LogQuery.MAX_CHARS].
 */
data class LogReadResult(
	val entries: List<LogEntry>,
	val truncated: Boolean,
) {
	companion object {
		@JvmField
		val EMPTY = LogReadResult(emptyList(), truncated = false)
	}
}
