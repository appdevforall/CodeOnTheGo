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

	/** Answers suggestIndexes: the path and the SQL. */
	var suggestAnswer: (path: String, sql: String) -> IndexSuggestions = { _, _ -> IndexSuggestions(null, emptyList()) }

	/** The sample of the last suggestIndexes. */
	var lastSample: Int? = null
		private set

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

	/** Each setRowCapture call, in order: rows per statement, value bytes, row bytes. */
	val rowCaptureCalls = mutableListOf<Triple<Int, Int, Int>>()

	/** The SQL and limits of each query, in order. */
	val queries = mutableListOf<Pair<String, QueryLimits>>()

	/** The argNames of the last query: null when its args were bound by position. */
	var lastArgNames: List<String>? = null
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

	/** Adds the event [make] builds for the next number, for the kinds and fields [addEvent] does not set. */
	fun addEvent(make: (seq: Long) -> Event) {
		lastSeq++
		buffered.addLast(make(lastSeq))
	}

	/** Drops the [count] oldest buffered events, as a full native buffer would. */
	fun dropOldest(count: Int) = repeat(count) { buffered.removeFirstOrNull() }

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

	override fun setRowCapture(
		maxRowsPerStatement: Int,
		maxValueBytes: Int,
		maxRowBytes: Int,
	) {
		rowCaptureCalls += Triple(maxRowsPerStatement, maxValueBytes, maxRowBytes)
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
				argNames: List<String>?,
				offset: Long,
				limits: QueryLimits,
				deadlineMillis: Long,
			): QueryResult {
				check(!closed) { "closed" }
				lastArgNames = argNames
				queries += sql to limits
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

			override fun suggestIndexes(
				sql: String,
				sample: Int,
				deadlineMillis: Long,
			): IndexSuggestions {
				check(!closed) { "closed" }
				lastSample = sample
				return suggestAnswer(path, sql)
			}

			override fun close() {
				if (!closed) closedServerConnections++
				closed = true
			}
		}
	}
}
