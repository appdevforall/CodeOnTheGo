package org.appdevforall.sqlitedebugger.engine

import java.io.Closeable

/**
 * What the MCP server (ADFA-7136) needs from the SQLite extension (ADFA-7135). The device
 * implementation is a thin JNI layer over `sqlitedbg.h`; JVM tests use a fake. Every call may
 * block briefly and must not run on the main thread.
 */
interface DebugEngine {
	/**
	 * An opaque string that stays the same for the life of the app process and differs in each process
	 * (random, never repeated after a restart). The event numbers start again at 1 in a new process, so a
	 * client keeps this with its [EventBatch.next] to tell a cursor of an earlier process from one of this.
	 */
	val runId: String

	/** The app's connections SQLite has not yet freed, oldest first. Server connections are not listed. */
	fun connections(): List<AppConnection>

	/**
	 * Events with [Event.seq] greater than [after], oldest first, up to about [maxBytes] of
	 * native records. Pass [EventBatch.next] as [after] next time; start from 0. An [after] at or past the
	 * next number to be assigned cannot be a cursor of this process: the read starts from 0 and
	 * [EventBatch.reset] is true.
	 */
	fun readEvents(
		after: Long,
		maxBytes: Int,
	): EventBatch

	/** While true, statements are recorded with their bound values written in, and rows if [setRowCapture] is on. */
	fun setClientConnected(connected: Boolean)

	/**
	 * Records the first [maxRowsPerStatement] rows of each statement as [EventKind.ROW] events while a
	 * client is connected; 0 turns it off, as at install. TEXT and BLOB values are cut at [maxValueBytes]
	 * and a row's data at [maxRowBytes] (`sqlitedbg_set_row_capture`).
	 */
	fun setRowCapture(
		maxRowsPerStatement: Int,
		maxValueBytes: Int,
		maxRowBytes: Int,
	)

	/**
	 * Opens [path] for the server: read-only (with an authorizer refusing writes) unless
	 * [writable]. Only a path one of [connections] has open may be passed: opening a database that
	 * Android's own SQLite has open, with requery's, can corrupt it (ADFA-7131, Design 3).
	 *
	 * @throws DebugEngineException if SQLite cannot open it, or no app connection has opened yet.
	 */
	fun openServerConnection(
		path: String,
		writable: Boolean,
	): ServerConnection
}

/** A connection the server opened with [DebugEngine.openServerConnection]. Use from one thread at a time. */
interface ServerConnection : Closeable {
	/**
	 * Runs [sql], which must hold exactly one statement (trailing semicolons and comments are fine),
	 * with [args] bound (null, [Long], [Double], [String] or [ByteArray]), skips
	 * [offset] rows, and returns at most [QueryLimits.maxRows]. Paging re-runs the statement, so a
	 * later page can differ if the data changed. Stops the statement (sqlite3_interrupt, through a
	 * progress handler on this server connection) once [deadlineMillis] (System.currentTimeMillis
	 * clock) has passed.
	 *
	 * [argNames] null binds [args] by position, `?NNN` by its number; otherwise `argNames[i]` names the
	 * parameter `args[i]` goes to, written as in the SQL (`:id`, `@id`, `$id`, `?3`) or bare (`id`, for
	 * any of `:`, `@` and `$`). Every parameter must get exactly one value and every name must be used.
	 *
	 * @throws DebugEngineException for SQLite errors, SQL with no statement or more than one, a
	 *   refused write, the deadline, or arguments that do not match the parameters (SQLITE_RANGE).
	 */
	fun query(
		sql: String,
		args: List<Any?>,
		argNames: List<String>?,
		offset: Long,
		limits: QueryLimits,
		deadlineMillis: Long,
	): QueryResult

	/**
	 * Index suggestions for [sql] (one or more statements) from SQLite's ext/expert (`sqlitedbg_expert_run`):
	 * the statements are prepared, never run, and no row of the app's data is read, so the suggestions
	 * rest on SQLite's default estimates. Stops at [deadlineMillis] like [query].
	 *
	 * @throws DebugEngineException for SQLite errors (SQL that does not prepare, for one) or the deadline.
	 */
	fun suggestIndexes(
		sql: String,
		deadlineMillis: Long,
	): IndexSuggestions
}

data class IndexSuggestions(
	/** The candidate indexes expert weighed, one CREATE INDEX per line with its estimate; null when none. */
	val candidates: String?,
	val statements: List<StatementSuggestion>,
)

data class StatementSuggestion(
	val sql: String,
	/** The CREATE INDEX statements that would help, one per line; null when none would. */
	val indexes: String?,
	/** EXPLAIN QUERY PLAN with those indexes, one line per step. */
	val plan: String?,
)

data class AppConnection(
	/** Unique within the process, never reused. */
	val id: Long,
	/** Empty for an in-memory or temporary database. */
	val path: String,
	val readOnly: Boolean,
	/** The app asked to close it; the close may still fail, so it stays listed until freed. */
	val closing: Boolean,
	val openedAtEpochNanos: Long,
	val openThreadId: Long,
)

enum class EventKind(
	/** The `kind` value in `sqlitedbg_event_record`. */
	val nativeValue: Int,
) {
	/** A statement starts; also each trigger it fires, whose SQL starts with "--". */
	STATEMENT(1),

	/** A statement finished; [Event.durationNanos] is set. */
	PROFILE(2),

	/** The app asked SQLite to close the connection; [Event.sql] is empty. */
	CLOSE(3),

	/**
	 * A statement returned a row, recorded only while a client is connected and row recording is on.
	 * [Event.row] holds its values, [Event.rows] its number in the statement; [Event.sql] is empty.
	 */
	ROW(4),
}

data class Event(
	/** 1, 2, 3...; a gap means events were dropped. */
	val seq: Long,
	val kind: EventKind,
	val connectionId: Long,
	val threadId: Long,
	val timeEpochNanos: Long,
	/** Set for [EventKind.PROFILE] only. */
	val durationNanos: Long?,
	val sql: String,
	/** [sql] was cut at the extension's limit, or for [EventKind.ROW], [row] was (see [RowValues]). */
	val truncated: Boolean,
	/** [sql] has its bound values written in. */
	val expanded: Boolean,
	/**
	 * [EventKind.PROFILE]: the rows the statement returned, recorded or not. [EventKind.ROW]: the row's
	 * number in its statement, from 1. Null otherwise, and for a statement the extension did not see start.
	 */
	val rows: Long? = null,
	/**
	 * [EventKind.PROFILE] of a statement that may write: the rows it changed itself, as
	 * `sqlite3_changes64` counts them (not those changed by triggers); 0 when it changed none. Null for a
	 * read-only statement and for the other kinds.
	 */
	val changes: Long? = null,
	/** [EventKind.PROFILE]: rows went unrecorded because the statement returned more than the per-statement limit. */
	val rowsCut: Boolean = false,
	/** [EventKind.ROW] only: the row's values. */
	val row: RowValues? = null,
)

data class RowValues(
	/** The statement's column count. */
	val columnCount: Int,
	/**
	 * The values kept, in column order: null, [Long], [Double], [String] or [ByteArray]. Fewer than
	 * [columnCount] when the row's data reached its limit.
	 */
	val values: List<Any?>,
	/** Indexes into [values] of TEXT and BLOB values that were cut at the value limit. */
	val cutIndexes: Set<Int>,
)

data class EventBatch(
	val events: List<Event>,
	/** Pass as `after` to the next [DebugEngine.readEvents]. */
	val next: Long,
	/** Events after the requested cursor that left the buffer before they were read. */
	val dropped: Long,
	/** The requested cursor was past every event number this process has assigned, so the read started from 0. */
	val reset: Boolean = false,
)

data class QueryLimits(
	val maxRows: Int,
	/** Longer TEXT values are cut at a UTF-8 character boundary and counted in [QueryResult.truncatedCells]. */
	val maxTextBytes: Int,
	/** Longer BLOB values are cut and counted in [QueryResult.truncatedCells]. */
	val maxBlobBytes: Int,
	/**
	 * A bound on the cell bytes of one result, so a wide SELECT cannot exhaust the app's heap: the
	 * TEXT and BLOB bytes kept, plus 8 for each number. Rows are added until the next one would pass
	 * it; the rest is [QueryResult.hasMore]. The first row is always returned.
	 */
	val maxTotalBytes: Int,
)

data class QueryResult(
	val columns: List<String>,
	/** Each value is null, [Long], [Double], [String] or [ByteArray]. Doubles may be NaN or infinite. */
	val rows: List<List<Any?>>,
	val truncatedCells: Int,
	/** More rows follow the last one returned: the row limit or [QueryLimits.maxTotalBytes] was reached. */
	val hasMore: Boolean,
	/**
	 * Which cells were cut, as `row * columns.size + column`: a BLOB of exactly [QueryLimits.maxBlobBytes]
	 * is whole and not listed, one byte more is. [truncatedCells] is its size.
	 */
	val truncatedCellIndexes: Set<Long> = emptySet(),
	/** Each column's declared type, as `sqlite3_column_decltype` gives it; null for an expression. */
	val declaredTypes: List<String?> = List(columns.size) { null },
) {
	fun isTruncated(
		row: Int,
		column: Int,
	) = (row.toLong() * columns.size + column) in truncatedCellIndexes
}

class DebugEngineException(
	/** The SQLite result code, or [SQLITE_MISUSE] when no app connection has opened yet. */
	val sqliteCode: Int,
	message: String,
) : Exception(message) {
	companion object {
		const val SQLITE_AUTH = 23
		const val SQLITE_INTERRUPT = 9
		const val SQLITE_MISUSE = 21
	}
}
