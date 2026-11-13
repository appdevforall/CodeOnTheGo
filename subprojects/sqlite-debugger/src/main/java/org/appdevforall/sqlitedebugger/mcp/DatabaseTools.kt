package org.appdevforall.sqlitedebugger.mcp

import org.appdevforall.sqlitedebugger.engine.DebugEngine
import org.appdevforall.sqlitedebugger.engine.DebugEngineException
import org.appdevforall.sqlitedebugger.engine.QueryLimits
import org.appdevforall.sqlitedebugger.engine.QueryResult
import org.appdevforall.sqlitedebugger.engine.ServerConnection
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The debugger's MCP tools, all read-only: they open server connections through [engine] with
 * `writable = false`, so SQLite's authorizer refuses a write whatever SQL a client sends. A tool
 * names a database by the path one of the app's own connections has open; any other path is
 * refused before the engine is called (opening a file Android's SQLite has open, with the
 * extension's SQLite, can corrupt it).
 */
class DatabaseTools(
	private val engine: DebugEngine,
	/** The app's database folder, to list files no connection has opened; null on the JVM. */
	private val databaseDir: File? = null,
	private val clock: () -> Long = System::currentTimeMillis,
) {
	companion object {
		const val DEFAULT_MAX_ROWS = 100
		const val MAX_ROWS = 1000
		const val MAX_TEXT_BYTES = 4096
		const val MAX_BLOB_BYTES = 1024

		/** The most cell bytes one tool result holds (the engine's `maxTotalBytes`); the rest is paged. */
		const val MAX_TOTAL_BYTES = 4 * 1024 * 1024

		/** Below the client's 60 s limit on a tool call. */
		const val DEADLINE_MS = 50_000L
		const val EVENT_BATCH_BYTES = 256 * 1024
		const val MAX_TABLES_FOR_COUNTS = 10

		private const val MAX_SAFE_INTEGER = 9007199254740992L // 2^53
		private val SIDE_FILE_SUFFIXES = listOf("-wal", "-shm", "-journal")

		const val PAGING_NOTE = "Paging re-runs the query for each page, so a later page can differ if the data changed."

		/** A SQLite value as JSON: org.json throws on NaN and infinities, and JSON numbers lose integers past 2^53. */
		fun encodeValue(
			value: Any?,
			truncated: Boolean = false,
		): Any =
			when (value) {
				null -> {
					JSONObject.NULL
				}

				is Long -> {
					encodeLong(value)
				}

				is Int -> {
					value.toLong()
				}

				is Double -> {
					when {
						value.isNaN() -> "NaN"
						value == Double.POSITIVE_INFINITY -> "Infinity"
						value == Double.NEGATIVE_INFINITY -> "-Infinity"
						else -> value
					}
				}

				is String -> {
					value
				}

				is ByteArray -> {
					JSONObject()
						.put("base64", McpHttp.base64(value, urlSafe = false, pad = true))
						.put("bytes", value.size)
						.put("truncated", truncated)
				}

				else -> {
					value.toString()
				}
			}

		fun encodeLong(value: Long): Any = if (value > MAX_SAFE_INTEGER || value < -MAX_SAFE_INTEGER) value.toString() else value

		fun quoteIdentifier(name: String) = "\"" + name.replace("\"", "\"\"") + "\""
	}

	private val lock = Any()
	private val serverConnections = HashMap<String, ServerConnection>()
	private var clientConnected = false

	/** Closes the server connections and tells the engine no client is watching. Call when the server stops. */
	fun onServerStopped() {
		synchronized(lock) {
			serverConnections.values.forEach { runCatching { it.close() } }
			serverConnections.clear()
			if (clientConnected) {
				clientConnected = false
				engine.setClientConnected(false)
			}
		}
	}

	fun tools(): List<McpTool> =
		listOf(
			tool(
				"list_databases",
				"List databases",
				"Lists the app's databases: each one an app connection has open (available), and the files in the app's database folder " +
					"that none has opened (not available: not inspectable until the app opens them).",
			) { listDatabases() },
			tool(
				"list_connections",
				"List connections",
				"Lists the app's open SQLite connections (not yet freed by SQLite), oldest first.",
			) { listConnections() },
			tool(
				"get_schema",
				"Get schema",
				"Returns the tables, indexes, views and triggers of a database, with their CREATE statements, from sqlite_master.",
				databaseSchema(),
			) { getSchema(it) },
			tool(
				"query",
				"Run a query",
				"Runs one read-only SQL statement. Returns at most max_rows rows (default $DEFAULT_MAX_ROWS, at most $MAX_ROWS); " +
					"TEXT cells are cut at $MAX_TEXT_BYTES bytes and BLOBs at $MAX_BLOB_BYTES. A result also stops at about " +
					"${MAX_TOTAL_BYTES / (1024 * 1024)} MiB of cell bytes (has_more is then true). Use offset to page. " +
					"$PAGING_NOTE Writes are refused.",
				databaseSchema(
					"sql" to prop("string", "The SQL statement."),
					"args" to JSONObject().put("type", "array").put("description", "Values for ? parameters: null, numbers or strings."),
					"offset" to prop("integer", "Rows to skip. Default 0."),
					"max_rows" to prop("integer", "Rows to return. Default $DEFAULT_MAX_ROWS, at most $MAX_ROWS."),
					required = listOf("sql"),
				),
			) { query(it) },
			tool(
				"explain_query_plan",
				"Explain a query plan",
				"Runs EXPLAIN QUERY PLAN on a SQL statement and returns SQLite's plan. The statement is not run.",
				databaseSchema(
					"sql" to prop("string", "The SQL statement."),
					"args" to JSONObject().put("type", "array").put("description", "Values for ? parameters."),
					required = listOf("sql"),
				),
			) { explain(it) },
			tool(
				"get_stats",
				"Get statistics",
				"Returns page_count, page_size, freelist_count, and the row count of each table when the database has fewer than " +
					"$MAX_TABLES_FOR_COUNTS tables.",
				databaseSchema(),
			) { getStats(it) },
			tool(
				"integrity_check",
				"Check integrity",
				"Runs PRAGMA quick_check, or the slower PRAGMA integrity_check when full is true. " +
					"ok is true when SQLite reports no problem.",
				databaseSchema("full" to prop("boolean", "Run the full integrity_check. Default false."), required = emptyList()),
			) { integrityCheck(it) },
			tool(
				"get_events",
				"Get SQL events",
				"Returns the SQL the app ran, oldest first, after the cursor. Pass the returned next as after to continue; " +
					"dropped counts events that left the buffer unread. The first call starts recording bound values into the SQL.",
				JSONObject()
					.put("type", "object")
					.put("additionalProperties", false)
					.put("properties", JSONObject().put("after", prop("integer", "Cursor: the next from the last call. Default 0.")))
					.put("required", JSONArray()),
			) { getEvents(it) },
		)

	private fun tool(
		name: String,
		title: String,
		description: String,
		inputSchema: JSONObject = JSONObject().put("type", "object").put("properties", JSONObject()).put("additionalProperties", false),
		run: (JSONObject) -> JSONObject,
	) = McpTool(name, title, description, inputSchema, readOnly = true, run = run)

	private fun prop(
		type: String,
		description: String,
	) = JSONObject().put("type", type).put("description", description)

	private fun databaseSchema(
		vararg extra: Pair<String, JSONObject>,
		required: List<String> = emptyList(),
	): JSONObject {
		val props = JSONObject().put("database", prop("string", "The database path, as list_databases gives it."))
		extra.forEach { props.put(it.first, it.second) }
		return JSONObject()
			.put("type", "object")
			.put("properties", props)
			.put("additionalProperties", false)
			.put("required", JSONArray(listOf("database") + required))
	}

	private fun listDatabases(): JSONObject {
		val connections = engine.connections()
		val byPath = LinkedHashMap<String, MutableList<Long>>()
		for (c in connections) if (c.path.isNotEmpty()) byPath.getOrPut(c.path) { mutableListOf() } += c.id
		val list = JSONArray()
		for ((path, ids) in byPath) {
			list.put(JSONObject().put("path", path).put("available", true).put("connections", ids.size))
		}
		databaseDir?.listFiles()?.sortedBy { it.name }?.forEach { f ->
			if (!f.isFile || SIDE_FILE_SUFFIXES.any { f.name.endsWith(it) }) return@forEach
			if (f.absolutePath in byPath || f.path in byPath) return@forEach
			list.put(JSONObject().put("path", f.absolutePath).put("available", false).put("connections", 0))
		}
		return JSONObject().put("databases", list)
	}

	private fun listConnections(): JSONObject {
		val list = JSONArray()
		for (c in engine.connections()) {
			list.put(
				JSONObject()
					.put("id", encodeLong(c.id))
					.put("path", c.path)
					.put("read_only", c.readOnly)
					.put("closing", c.closing)
					.put("opened_at_epoch_nanos", encodeLong(c.openedAtEpochNanos))
					.put("open_thread_id", encodeLong(c.openThreadId)),
			)
		}
		return JSONObject().put("connections", list)
	}

	private fun getSchema(args: JSONObject): JSONObject {
		val result =
			run(
				args,
				"SELECT type, name, tbl_name, sql FROM sqlite_master ORDER BY type, name",
				emptyList(),
				0,
				QueryLimits(MAX_ROWS, 65536, MAX_BLOB_BYTES, MAX_TOTAL_BYTES),
			)
		val groups = linkedMapOf("table" to JSONArray(), "index" to JSONArray(), "view" to JSONArray(), "trigger" to JSONArray())
		for (row in result.rows) {
			val item = JSONObject().put("name", encodeValue(row[1])).put("table", encodeValue(row[2])).put("sql", encodeValue(row[3]))
			groups[row[0] as? String]?.put(item)
		}
		return JSONObject()
			.put("tables", groups["table"])
			.put("indexes", groups["index"])
			.put("views", groups["view"])
			.put("triggers", groups["trigger"])
			.put("truncated", result.hasMore)
	}

	private fun query(args: JSONObject): JSONObject {
		val sql = stringArg(args, "sql")
		val offset = intArg(args, "offset", 0, 0, Long.MAX_VALUE)
		val maxRows = intArg(args, "max_rows", DEFAULT_MAX_ROWS.toLong(), 1, MAX_ROWS.toLong()).toInt()
		val result = run(args, sql, bindArgs(args), offset, QueryLimits(maxRows, MAX_TEXT_BYTES, MAX_BLOB_BYTES, MAX_TOTAL_BYTES))
		val out = encodeResult(result, offset)
		out.put("note", PAGING_NOTE)
		return out
	}

	private fun explain(args: JSONObject): JSONObject {
		val result =
			run(
				args,
				"EXPLAIN QUERY PLAN " + stringArg(args, "sql"),
				bindArgs(args),
				0,
				QueryLimits(MAX_ROWS, MAX_TEXT_BYTES, MAX_BLOB_BYTES, MAX_TOTAL_BYTES),
			)
		return JSONObject().put("plan", rowsAsObjects(result))
	}

	private fun getStats(args: JSONObject): JSONObject {
		val limits = QueryLimits(MAX_ROWS, MAX_TEXT_BYTES, MAX_BLOB_BYTES, MAX_TOTAL_BYTES)
		val deadline = clock() + DEADLINE_MS
		val path = checkedPath(args)

		fun pragma(name: String) = runChecked(path, "PRAGMA $name", emptyList(), 0, limits, deadline).rows.firstOrNull()?.firstOrNull()
		val pageCount = pragma("page_count") as? Long
		val pageSize = pragma("page_size") as? Long
		val out =
			JSONObject()
				.put("page_count", encodeValue(pageCount))
				.put("page_size", encodeValue(pageSize))
				.put("freelist_count", encodeValue(pragma("freelist_count")))
		if (pageCount != null && pageSize != null) out.put("size_bytes", encodeLong(pageCount * pageSize))
		val tables =
			runChecked(
				path,
				"SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name",
				emptyList(),
				0,
				limits,
				deadline,
			).rows
				.map { it[0] as String }
		if (tables.size < MAX_TABLES_FOR_COUNTS) {
			val counts = JSONObject()
			for (t in tables) {
				val n =
					try {
						runChecked(
							path,
							"SELECT COUNT(*) FROM ${quoteIdentifier(t)}",
							emptyList(),
							0,
							limits,
							deadline,
						).rows.firstOrNull()?.firstOrNull()
					} catch (e: McpToolError) {
						e.message
					}
				counts.put(t, encodeValue(n))
			}
			out.put("row_counts", counts)
		} else {
			out.put("row_counts_skipped", "The database has ${tables.size} tables; counts are given only under $MAX_TABLES_FOR_COUNTS.")
		}
		return out
	}

	private fun integrityCheck(args: JSONObject): JSONObject {
		val full =
			when (val v = args.opt("full")) {
				null, JSONObject.NULL -> false
				is Boolean -> v
				else -> throw McpToolError("full must be true or false.")
			}
		val pragma = if (full) "PRAGMA integrity_check" else "PRAGMA quick_check"
		val result = run(args, pragma, emptyList(), 0, QueryLimits(MAX_ROWS, MAX_TEXT_BYTES, MAX_BLOB_BYTES, MAX_TOTAL_BYTES))
		val messages = result.rows.map { it.firstOrNull()?.toString() ?: "" }
		return JSONObject()
			.put("check", if (full) "integrity_check" else "quick_check")
			.put("ok", messages == listOf("ok"))
			.put("messages", JSONArray(messages))
	}

	private fun getEvents(args: JSONObject): JSONObject {
		val after = intArg(args, "after", 0, 0, Long.MAX_VALUE)
		synchronized(lock) {
			if (!clientConnected) {
				engine.setClientConnected(true)
				clientConnected = true
			}
		}
		val batch =
			try {
				engine.readEvents(after, EVENT_BATCH_BYTES)
			} catch (e: DebugEngineException) {
				throw engineError(e)
			}
		val events = JSONArray()
		for (e in batch.events) {
			events.put(
				JSONObject()
					.put("seq", encodeLong(e.seq))
					.put("kind", e.kind.name.lowercase())
					.put("connection_id", encodeLong(e.connectionId))
					.put("thread_id", encodeLong(e.threadId))
					.put("time_epoch_nanos", encodeLong(e.timeEpochNanos))
					.put("duration_nanos", e.durationNanos?.let { encodeLong(it) } ?: JSONObject.NULL)
					.put("sql", e.sql)
					.put("truncated", e.truncated)
					.put("expanded", e.expanded),
			)
		}
		return JSONObject().put("next", encodeLong(batch.next)).put("dropped", encodeLong(batch.dropped)).put("events", events)
	}

	private fun encodeResult(
		result: QueryResult,
		offset: Long,
	): JSONObject {
		val rows = JSONArray()
		result.rows.forEachIndexed { r, row ->
			rows.put(JSONArray().also { a -> row.forEachIndexed { c, v -> a.put(encodeValue(v, result.isTruncated(r, c))) } })
		}
		val out =
			JSONObject()
				.put("columns", JSONArray(result.columns))
				.put("rows", rows)
				.put("row_count", result.rows.size)
				.put("offset", encodeLong(offset))
				.put("has_more", result.hasMore)
				.put("truncated_cells", result.truncatedCells)
		if (result.hasMore) out.put("next_offset", encodeLong(offset + result.rows.size))
		return out
	}

	private fun rowsAsObjects(result: QueryResult): JSONArray {
		val arr = JSONArray()
		result.rows.forEachIndexed { r, row ->
			val o = JSONObject()
			result.columns.forEachIndexed { i, c -> o.put(c, encodeValue(row[i], result.isTruncated(r, i))) }
			arr.put(o)
		}
		return arr
	}

	private fun checkedPath(args: JSONObject): String {
		val path = stringArg(args, "database")
		val known =
			engine
				.connections()
				.map { it.path }
				.filter { it.isNotEmpty() }
				.distinct()
		if (path !in known) {
			throw McpToolError(
				"Unknown database \"$path\": use a path from list_databases that is available.",
				JSONObject().put("available", JSONArray(known)),
			)
		}
		return path
	}

	private fun run(
		args: JSONObject,
		sql: String,
		bind: List<Any?>,
		offset: Long,
		limits: QueryLimits,
	): QueryResult = runChecked(checkedPath(args), sql, bind, offset, limits, clock() + DEADLINE_MS)

	private fun runChecked(
		path: String,
		sql: String,
		bind: List<Any?>,
		offset: Long,
		limits: QueryLimits,
		deadline: Long,
	): QueryResult =
		synchronized(lock) {
			try {
				val conn = serverConnections.getOrPut(path) { engine.openServerConnection(path, writable = false) }
				conn.query(sql, bind, offset, limits, deadline)
			} catch (e: DebugEngineException) {
				throw engineError(e)
			}
		}

	private fun engineError(e: DebugEngineException) =
		McpToolError(e.message ?: "SQLite error ${e.sqliteCode}", JSONObject().put("sqlite_code", e.sqliteCode))

	private fun stringArg(
		args: JSONObject,
		name: String,
	): String {
		val v = args.opt(name)
		if (v !is String || v.isEmpty()) throw McpToolError("$name is required and must be a non-empty string.")
		return v
	}

	private fun intArg(
		args: JSONObject,
		name: String,
		default: Long,
		min: Long,
		max: Long,
	): Long {
		val v = args.opt(name)
		if (v == null || v == JSONObject.NULL) return default
		val n = v as? Number ?: throw McpToolError("$name must be an integer.")
		// org.json parses a decimal as BigDecimal, not Double
		if (n !is Int && n !is Long && n !is Short && n !is Byte) {
			val d = n.toDouble()
			if (d != Math.floor(d) || d.isInfinite() || Math.abs(d) >= 9.2e18) throw McpToolError("$name must be an integer.")
		}
		val l = n.toLong()
		val upper = if (max == Long.MAX_VALUE) "2^63-1" else max.toString()
		if (l < min || l > max) throw McpToolError("$name must be between $min and $upper.")
		return l
	}

	private fun bindArgs(args: JSONObject): List<Any?> {
		val raw = args.opt("args")
		if (raw == null || raw == JSONObject.NULL) return emptyList()
		val arr = raw as? JSONArray ?: throw McpToolError("args must be an array.")
		return (0 until arr.length()).map { i ->
			when (val v = arr.opt(i)) {
				null, JSONObject.NULL -> null
				is Int, is Long, is Short -> (v as Number).toLong()
				is Number -> v.toDouble()
				is String -> v
				is Boolean -> if (v) 1L else 0L
				else -> throw McpToolError("args[$i] must be null, a number or a string.")
			}
		}
	}
}
