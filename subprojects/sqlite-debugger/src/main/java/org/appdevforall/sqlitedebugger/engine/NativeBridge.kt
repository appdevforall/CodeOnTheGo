package org.appdevforall.sqlitedebugger.engine

/**
 * The JNI entry points of libsqlitedbg.so, registered by name in its JNI_OnLoad (sqlitedbg_jni.c).
 * Signatures there must match these. Only [NativeDebugEngine] calls them.
 */
internal object NativeBridge {
	/** Registers the extension with requery's SQLite. 0 (SQLITE_OK) or the SQLite code; -1 if libsqlite3x.so is not loaded. */
	@JvmStatic
	external fun install(): Int

	/** `[LongArray, Array<String>]`: 5 longs per connection (id, readOnly, closing, openedAtNs, openThreadId), and the paths. */
	@JvmStatic
	external fun connections(): Array<Any?>

	/** Fills [buf] with records after [after]; [out] (4 longs) gets next, dropped, the bytes written and reset (0 or 1). */
	@JvmStatic
	external fun readEvents(
		after: Long,
		buf: ByteArray,
		out: LongArray,
	)

	@JvmStatic
	external fun setClientConnected(connected: Boolean)

	/**
	 * Turns row recording on (`maxRowsPerStatement` > 0) or off (0): `sqlitedbg_set_row_capture`. A value
	 * or row limit of 0 takes the extension's default.
	 */
	@JvmStatic
	external fun setRowCapture(
		maxRowsPerStatement: Int,
		maxValueBytes: Int,
		maxRowBytes: Int,
	)

	/** A handle for [query] and [close]. Throws [DebugEngineException]. */
	@JvmStatic
	external fun openServer(
		path: ByteArray,
		writable: Boolean,
	): Long

	/**
	 * `[Array<String>, ArrayList<Array<Any?>>, LongArray, Array<String?>]`: the column names, the rows,
	 * `{truncatedCells, hasMore, then the index of each cut cell}` (row * columns + column), and each
	 * column's declared type. [names] null binds [args] by position; otherwise `names[i]` (UTF-8) names
	 * the parameter `args[i]` goes to. Throws [DebugEngineException].
	 */
	@JvmStatic
	external fun query(
		handle: Long,
		sql: String,
		args: Array<Any?>,
		names: Array<ByteArray>?,
		offset: Long,
		maxRows: Int,
		maxTextBytes: Int,
		maxBlobBytes: Int,
		maxTotalBytes: Int,
		deadlineMillis: Long,
	): Array<Any?>

	/**
	 * Index suggestions from SQLite's ext/expert for [sqlUtf8] (one or more statements, UTF-8), which is
	 * prepared but never run: `[candidates, then sql, indexes and plan for each statement]`. Throws
	 * [DebugEngineException].
	 */
	@JvmStatic
	external fun suggestIndexes(
		handle: Long,
		sqlUtf8: ByteArray,
		deadlineMillis: Long,
	): Array<String?>

	@JvmStatic
	external fun close(handle: Long)
}
