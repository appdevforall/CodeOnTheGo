package org.appdevforall.sqlitedebugger.engine

/**
 * [DebugEngine] over the JNI layer of libsqlitedbg.so. Needs requery's libsqlite3x.so in the process:
 * the extension runs inside it, on connections it opens after [install]. Call [install] before
 * anything else; the native state is per process, so every instance shares it.
 */
class NativeDebugEngine : DebugEngine {
	/**
	 * Loads requery's SQLite and the debugger's library and registers the extension. Connections
	 * opened before this are never seen, so call it as early as the process allows. Calling it
	 * again, on any instance, does nothing.
	 *
	 * @throws DebugEngineException if SQLite refuses the extension.
	 * @throws UnsatisfiedLinkError if a library is missing.
	 */
	fun install() {
		synchronized(lock) {
			if (installed) return
			System.loadLibrary("sqlite3x")
			System.loadLibrary("sqlitedbg")
			val rc = NativeBridge.install()
			if (rc != 0) {
				throw DebugEngineException(
					if (rc < 0) DebugEngineException.SQLITE_MISUSE else rc,
					if (rc < 0) "libsqlite3x.so is not loaded" else "sqlite3_auto_extension failed with $rc",
				)
			}
			installed = true
		}
	}

	override fun connections(): List<AppConnection> {
		val result = NativeBridge.connections()

		@Suppress("UNCHECKED_CAST")
		val paths = result[1] as Array<String>
		val meta = result[0] as LongArray
		return paths.indices.map { i ->
			val m = i * META_PER_CONNECTION
			AppConnection(
				id = meta[m],
				path = paths[i],
				readOnly = meta[m + 1] != 0L,
				closing = meta[m + 2] != 0L,
				openedAtEpochNanos = meta[m + 3],
				openThreadId = meta[m + 4],
			)
		}
	}

	override fun readEvents(
		after: Long,
		maxBytes: Int,
	): EventBatch {
		val buffer = ByteArray(maxBytes.coerceIn(1, MAX_READ_BYTES))
		val out = LongArray(3)
		NativeBridge.readEvents(after, buffer, out)
		return EventBatch(events = EventDecoder.decode(buffer, out[2].toInt()), next = out[0], dropped = out[1])
	}

	override fun setClientConnected(connected: Boolean) = NativeBridge.setClientConnected(connected)

	override fun openServerConnection(
		path: String,
		writable: Boolean,
	): ServerConnection = NativeServerConnection(NativeBridge.openServer(path.toByteArray(Charsets.UTF_8), writable))

	private class NativeServerConnection(
		private var handle: Long,
	) : ServerConnection {
		override fun query(
			sql: String,
			args: List<Any?>,
			offset: Long,
			limits: QueryLimits,
			deadlineMillis: Long,
		): QueryResult {
			check(handle != 0L) { "the connection is closed" }
			require(offset >= 0) { "offset $offset is negative" }
			require(limits.maxRows > 0 && limits.maxTextBytes >= 0 && limits.maxBlobBytes >= 0 && limits.maxTotalBytes > 0) {
				"bad limits $limits"
			}
			val result =
				NativeBridge.query(
					handle,
					sql,
					Array(args.size) { bindable(args[it]) },
					offset,
					limits.maxRows,
					limits.maxTextBytes,
					limits.maxBlobBytes,
					limits.maxTotalBytes,
					deadlineMillis,
				)

			@Suppress("UNCHECKED_CAST")
			val columns = (result[0] as Array<String>).asList()

			@Suppress("UNCHECKED_CAST")
			val rows = (result[1] as List<Array<Any?>>).map { it.asList() }
			val counts = result[2] as LongArray
			val cut = HashSet<Long>()
			for (i in 2 until counts.size) cut += counts[i]
			return QueryResult(columns, rows, truncatedCells = counts[0].toInt(), hasMore = counts[1] != 0L, truncatedCellIndexes = cut)
		}

		override fun close() {
			val h = handle
			handle = 0
			if (h != 0L) NativeBridge.close(h)
		}

		private fun bindable(value: Any?): Any? =
			when (value) {
				null, is Long, is Double, is String, is ByteArray -> value
				is Int -> value.toLong()
				is Short -> value.toLong()
				is Byte -> value.toLong()
				is Float -> value.toDouble()
				else -> throw IllegalArgumentException("cannot bind a ${value.javaClass.name}")
			}
	}

	private companion object {
		const val META_PER_CONNECTION = 5
		const val MAX_READ_BYTES = 16 shl 20

		val lock = Any()
		var installed = false
	}
}
