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

	/** A handle for [query] and [close]. Throws [DebugEngineException]. */
	@JvmStatic
	external fun openServer(
		path: ByteArray,
		writable: Boolean,
	): Long

	/**
	 * `[Array<String>, ArrayList<Array<Any?>>, LongArray]`: the column names, the rows, and
	 * `{truncatedCells, hasMore, then the index of each cut cell}` (row * columns + column).
	 * Throws [DebugEngineException].
	 */
	@JvmStatic
	external fun query(
		handle: Long,
		sql: String,
		args: Array<Any?>,
		offset: Long,
		maxRows: Int,
		maxTextBytes: Int,
		maxBlobBytes: Int,
		maxTotalBytes: Int,
		deadlineMillis: Long,
	): Array<Any?>

	/**
	 * `{changes, last_insert_rowid}` of one statement that writes (see [ServerConnection.execute]); the
	 * rowid is 0 when the statement inserted no row into a rowid table. Throws [DebugEngineException].
	 */
	@JvmStatic
	external fun execute(
		handle: Long,
		sql: String,
		args: Array<Any?>,
		deadlineMillis: Long,
	): LongArray

	@JvmStatic
	external fun close(handle: Long)
}
