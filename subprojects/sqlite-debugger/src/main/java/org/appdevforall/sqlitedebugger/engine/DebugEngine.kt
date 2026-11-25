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

	/** While true, statements are recorded with their bound values written in. */
	fun setClientConnected(connected: Boolean)

	/**
	 * Opens [path] for the server: read-only (an authorizer refuses writes) unless [writable]. Either
	 * way the authorizer refuses transactions, the PRAGMAs that would reach the app's own
	 * connections and every PRAGMA that sets a value, user_version on a writable one excepted (sqlitedbg.h).
	 * Only a path one of [connections] has open may be passed: opening a
	 * database that Android's own SQLite has open, with requery's, can corrupt it (ADFA-7131, Design 3).
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
	 * @throws DebugEngineException for SQLite errors, SQL with no statement or more than one, a
	 *   refused write, or the deadline.
	 */
	fun query(
		sql: String,
		args: List<Any?>,
		offset: Long,
		limits: QueryLimits,
		deadlineMillis: Long,
	): QueryResult

	/**
	 * Runs [sql], one statement that writes, to its end in autocommit, with [args] bound as in [query]; rows it
	 * returns (RETURNING) are dropped. Needs a connection opened with `writable = true`. The statement is
	 * stopped and rolled back once [deadlineMillis] has passed, or after SQLITEDBG_SERVER_NON_WAL_MS
	 * (1.5 s) whichever is first: it holds the write lock, which the app's own writes wait for.
	 *
	 * @throws DebugEngineException as [query] does, and for a statement that does not write
	 *   (`sqlite3_stmt_readonly`), a transaction statement, or a refused PRAGMA.
	 */
	fun execute(
		sql: String,
		args: List<Any?>,
		deadlineMillis: Long,
	): ExecuteResult
}

data class ExecuteResult(
	/**
	 * The rows the statement itself inserted, updated or deleted, as `sqlite3_changes64` (not those of
	 * triggers or foreign key actions); 0 for a statement that changed no row, such as CREATE TABLE.
	 */
	val changes: Long,
	/**
	 * The rowid of the last row the statement inserted into a rowid table, or null when it inserted none
	 * (an UPDATE, a WITHOUT ROWID table, `INSERT OR IGNORE` that ignored the row). Inserts made by
	 * triggers do not count. A row inserted explicitly with rowid [Long.MIN_VALUE], which SQLite never
	 * assigns itself, reads as null; 0 and other negative rowids are reported.
	 */
	val lastInsertRowid: Long?,
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
	/** [sql] was cut at the extension's limit. */
	val truncated: Boolean,
	/** [sql] has its bound values written in. */
	val expanded: Boolean,
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
