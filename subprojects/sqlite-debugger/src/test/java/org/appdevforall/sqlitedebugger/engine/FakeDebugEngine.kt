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

	override var runId = "run-1"
		private set

	/** What a new app process looks like to a client: another [runId], and the event numbers start again at 1. */
	fun restartProcess(newRunId: String) {
		runId = newRunId
		buffered.clear()
		lastSeq = 0
	}

	/** Events still in the buffer, oldest first; [lastSeq] counts every event made. */
	private val buffered = ArrayDeque<Event>()
	private var lastSeq = 0L

	var clientConnected = false
		private set

	val openedServerConnections = mutableListOf<Pair<String, Boolean>>()

	/** How many times the server asked to open one, counted before any refusal: [openedServerConnections] holds only successes. */
	var openAttempts = 0
		private set

	/** How many server connections were closed. */
	var closedServerConnections = 0
		private set

	fun addEvent(
		kind: EventKind,
		sql: String,
		connectionId: Long = 1,
		durationNanos: Long? = null,
	) {
		lastSeq++
		buffered.addLast(Event(lastSeq, kind, connectionId, 1, lastSeq, durationNanos, sql, false, clientConnected))
	}

	/** Adds [event] as the next one, numbered after the events before it whatever its own [Event.seq]. */
	fun addEvent(event: Event) {
		lastSeq++
		buffered.addLast(event.copy(seq = lastSeq))
	}

	/** Drops the [count] oldest buffered events, as a full native buffer would. */
	fun dropOldest(count: Int) = repeat(count) { buffered.removeFirstOrNull() }

	/** A record's data as [EventDecoder] reads it: the SQL, or a ROW event's values, each with its header. */
	private fun dataBytes(event: Event): Int {
		val row = event.row ?: return event.sql.toByteArray().size
		return EventDecoder.ROW_HEADER_BYTES + row.values.sumOf { EventDecoder.ROW_VALUE_BYTES + cellBytes(it).toInt() }
	}

	private fun cellBytes(value: Any?): Long =
		when (value) {
			is String -> value.toByteArray().size.toLong()
			is ByteArray -> value.size.toLong()
			is Long, is Int, is Double -> 8
			else -> 0
		}

	override fun connections() = appConnections.toList()

	override fun readEvents(
		after: Long,
		maxBytes: Int,
	): EventBatch {
		val reset = after >= lastSeq + 1
		if (reset) return readEvents(0, maxBytes).copy(reset = true)
		val oldest = buffered.firstOrNull()?.seq ?: (lastSeq + 1)
		val dropped = (oldest - 1 - after).coerceAtLeast(0)
		val events = mutableListOf<Event>()
		var bytes = 0
		for (event in buffered) {
			if (event.seq <= after) continue
			// the native record: the header, then its data padded to 8 bytes
			val size = EventDecoder.HEADER_BYTES + (dataBytes(event) + 7) / 8 * 8
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
		openAttempts++
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
				val skip = offset.toInt().coerceAtMost(all.rows.size)
				val rest = all.rows.drop(skip)
				// the native rule: the first row always goes in, then rows while the total stays within the budget
				var taken = 0
				var total = 0L
				for (row in rest) {
					if (taken >= limits.maxRows) break
					val bytes = row.sumOf { cellBytes(it) }
					if (taken > 0 && total + bytes > limits.maxTotalBytes) break
					total += bytes
					taken++
				}
				val columns = all.columns.size
				val cut =
					all.truncatedCellIndexes
						.map { it - skip.toLong() * columns }
						.filter { it >= 0 && it < taken.toLong() * columns }
						.toSet()
				return all.copy(
					rows = rest.take(taken),
					hasMore = rest.size > taken,
					truncatedCells = if (all.truncatedCellIndexes.isEmpty()) all.truncatedCells else cut.size,
					truncatedCellIndexes = cut,
				)
			}

			override fun close() {
				if (!closed) closedServerConnections++
				closed = true
			}
		}
	}
}
