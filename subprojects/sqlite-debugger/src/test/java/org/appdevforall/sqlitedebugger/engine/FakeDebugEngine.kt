package org.appdevforall.sqlitedebugger.engine

/**
 * A [DebugEngine] for JVM tests of the server (ADFA-7136): connections and events are set by the
 * test, and each query is answered by [answer]. It keeps the real engine's rules that the server
 * relies on: cursors and dropped counts, and refusing to open a path no app connection has open.
 */
class FakeDebugEngine(
	var answer: (path: String, writable: Boolean, sql: String, args: List<Any?>) -> QueryResult = { _, _, _, _ ->
		QueryResult(emptyList(), emptyList(), 0, false)
	},
) : DebugEngine {
	val appConnections = mutableListOf<AppConnection>()

	/** Events still in the buffer, oldest first; [lastSeq] counts every event made. */
	private val buffered = ArrayDeque<Event>()
	private var lastSeq = 0L

	var clientConnected = false
		private set

	val openedServerConnections = mutableListOf<Pair<String, Boolean>>()

	fun addEvent(
		kind: EventKind,
		sql: String,
		connectionId: Long = 1,
		durationNanos: Long? = null,
	) {
		lastSeq++
		buffered.addLast(Event(lastSeq, kind, connectionId, 1, lastSeq, durationNanos, sql, false, clientConnected))
	}

	/** Drops the [count] oldest buffered events, as a full native buffer would. */
	fun dropOldest(count: Int) = repeat(count) { buffered.removeFirstOrNull() }

	override fun connections() = appConnections.toList()

	override fun readEvents(
		after: Long,
		maxBytes: Int,
	): EventBatch {
		val oldest = buffered.firstOrNull()?.seq ?: (lastSeq + 1)
		val dropped = (oldest - 1 - after).coerceAtLeast(0)
		val events = mutableListOf<Event>()
		var bytes = 0
		for (event in buffered) {
			if (event.seq <= after) continue
			// the native record: a 56-byte header, then the SQL padded to 8 bytes
			val size = 56 + (event.sql.toByteArray().size + 7) / 8 * 8
			if (bytes + size > maxBytes) break
			bytes += size
			events += event
		}
		return EventBatch(events, events.lastOrNull()?.seq ?: maxOf(after, oldest - 1), dropped)
	}

	override fun setClientConnected(connected: Boolean) {
		clientConnected = connected
	}

	override fun openServerConnection(
		path: String,
		writable: Boolean,
	): ServerConnection {
		if (appConnections.isEmpty()) {
			throw DebugEngineException(DebugEngineException.SQLITE_MISUSE, "no app connection has opened yet")
		}
		if (appConnections.none { it.path == path && path.isNotEmpty() }) {
			throw DebugEngineException(14, "unable to open database file: $path") // SQLITE_CANTOPEN
		}
		openedServerConnections += path to writable
		return object : ServerConnection {
			private var closed = false

			override fun query(
				sql: String,
				args: List<Any?>,
				offset: Long,
				limits: QueryLimits,
				deadlineMillis: Long,
			): QueryResult {
				check(!closed) { "closed" }
				val all = answer(path, writable, sql, args)
				val rows = all.rows.drop(offset.toInt())
				return all.copy(rows = rows.take(limits.maxRows), hasMore = rows.size > limits.maxRows)
			}

			override fun close() {
				closed = true
			}
		}
	}
}
