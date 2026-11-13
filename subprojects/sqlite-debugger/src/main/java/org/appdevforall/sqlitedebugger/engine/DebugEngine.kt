package org.appdevforall.sqlitedebugger.engine

import java.io.Closeable

/**
 * What the MCP server (ADFA-7136) needs from the SQLite extension (ADFA-7135). The device
 * implementation is a thin JNI layer over `sqlitedbg.h`; JVM tests use a fake. Every call may
 * block briefly and must not run on the main thread.
 */
interface DebugEngine {
	/** The app's connections SQLite has not yet freed, oldest first. Server connections are not listed. */
	fun connections(): List<AppConnection>

	/**
	 * Events with [Event.seq] greater than [after], oldest first, up to about [maxBytes] of
	 * native records. Pass [EventBatch.next] as [after] next time; start from 0.
	 */
	fun readEvents(
		after: Long,
		maxBytes: Int,
	): EventBatch

	/** While true, statements are recorded with their bound values written in. */
	fun setClientConnected(connected: Boolean)

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
	 * Runs [sql] with [args] bound (null, [Long], [Double], [String] or [ByteArray]), skips
	 * [offset] rows, and returns at most [QueryLimits.maxRows]. Paging re-runs the statement, so a
	 * later page can differ if the data changed. Stops the statement (sqlite3_interrupt, through a
	 * progress handler on this server connection) once [deadlineMillis] (System.currentTimeMillis
	 * clock) has passed.
	 *
	 * @throws DebugEngineException for SQLite errors, a refused write, or the deadline.
	 */
	fun query(
		sql: String,
		args: List<Any?>,
		offset: Long,
		limits: QueryLimits,
		deadlineMillis: Long,
	): QueryResult
}

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
