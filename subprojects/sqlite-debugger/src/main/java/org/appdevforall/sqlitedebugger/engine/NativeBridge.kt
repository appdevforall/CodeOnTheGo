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

	/** Fills [buf] with records after [after]; [out] gets next, dropped and the bytes written. */
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
	 * `{truncatedCells, hasMore}`. Throws [DebugEngineException].
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
		deadlineMillis: Long,
	): Array<Any?>

	@JvmStatic
	external fun close(handle: Long)
}
