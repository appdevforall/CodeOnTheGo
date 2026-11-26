package org.appdevforall.sqlitedebugger.mcp

import org.appdevforall.sqlitedebugger.engine.AppConnection
import org.appdevforall.sqlitedebugger.engine.DebugEngine
import org.appdevforall.sqlitedebugger.engine.DebugEngineException
import org.appdevforall.sqlitedebugger.engine.Event
import org.appdevforall.sqlitedebugger.engine.EventKind
import org.appdevforall.sqlitedebugger.engine.QueryLimits
import org.appdevforall.sqlitedebugger.engine.QueryResult
import org.appdevforall.sqlitedebugger.engine.ServerConnection
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Timer
import java.util.TimerTask

/**
 * The debugger's MCP tools, all read-only: they open server connections through [engine] with
 * `writable = false`, so SQLite's authorizer refuses a write whatever SQL a client sends. A tool
 * names a database by the path one of the app's own connections has open; any other path is
 * refused before the engine is called (opening a file Android's SQLite has open, with the
 * extension's SQLite, can corrupt it).
 *
 * One lock guards the cached server connections and every query, so tool calls run one at a time:
 * a slow query (up to [DEADLINE_MS]) makes the others wait. That is accepted: the debugger serves
 * one agent, and a connection per call would open the app's file many times over. The time limit
 * belongs to the POST ([McpProtocol] passes it in): it starts when the POST is handled and is shared
 * by every call of a batch, so waits for the lock count and the server gives up before the client
 * does; a call that waited at least half the limit says so in its error. A call that starts after
 * the limit fails at once, as the engine refuses to start a query past its deadline; the tools that
 * run no SQL (list_databases, list_connections, get_events) still answer, as they are quick.
 */
class DatabaseTools internal constructor(
	private val engine: DebugEngine,
	/** The app's database folder, to list files no connection has opened; null on the JVM. */
	private val databaseDir: File?,
	private val clock: () -> Long,
	private val idleScheduler: IdleScheduler,
) {
	constructor(
		engine: DebugEngine,
		databaseDir: File? = null,
		clock: () -> Long = System::currentTimeMillis,
	) : this(engine, databaseDir, clock, TimerIdleScheduler())

	companion object {
		const val DEFAULT_MAX_ROWS = 100
		const val MAX_ROWS = 1000
		const val MAX_TEXT_BYTES = 4096
		const val MAX_BLOB_BYTES = 1024

		/** get_schema's limit on a CREATE statement, well above [MAX_TEXT_BYTES]. */
		const val MAX_SCHEMA_SQL_BYTES = 65536

		/**
		 * The most cell bytes one tool result holds (the engine's `maxTotalBytes`); the rest is paged. The first
		 * row always comes back whole, so one wider row passes it: in query, up to 2000 columns (SQLite's default
		 * SQLITE_MAX_COLUMN, which requery keeps) of [MAX_TEXT_BYTES] each, about 7.8 MiB.
		 */
		const val MAX_TOTAL_BYTES = 4 * 1024 * 1024

		/**
		 * The time limit of a POST, from when it is handled, waits for the lock included and shared by every call
		 * of a batch: below the client's 60 s limit, so a query never holds the lock after the client has stopped
		 * waiting. A call made outside a POST that sets a limit starts its own.
		 */
		const val DEADLINE_MS = 50_000L
		private const val DEADLINE_SECONDS = DEADLINE_MS / 1000
		const val EVENT_BATCH_BYTES = 256 * 1024
		const val MAX_TABLES_FOR_COUNTS = 10

		/** Bound values stop being recorded this long after the last get_events call. */
		const val EVENTS_IDLE_MS = 60_000L

		/** export's limit on one TEXT or BLOB value: higher than query's, as a cut value is wrong data in an export. */
		const val EXPORT_VALUE_BYTES = 64 * 1024

		/** dump's limit on one statement, a single TEXT cell; a longer one is cut and listed in truncated_statements. */
		const val DUMP_STATEMENT_BYTES = MAX_TOTAL_BYTES

		/** get_events' row recording: rows per statement, bytes per TEXT or BLOB value, and bytes per row. */
		const val EVENT_ROWS_PER_STATEMENT = 10
		const val EVENT_VALUE_BYTES = 256
		const val EVENT_ROW_BYTES = 4096

		private const val MAX_SAFE_INTEGER = 9007199254740992L // 2^53
		private val SIDE_FILE_SUFFIXES = listOf("-wal", "-shm", "-journal")

		const val PAGING_NOTE = "Paging re-runs the query for each page, so a later page can differ if the data changed."

		/** How a cell is written, said by every tool that returns cells. */
		const val VALUES_NOTE =
			"A NULL is JSON null; a BLOB is {hex, bytes, truncated}; an integer beyond 2^53 is a decimal string, and a " +
				"NaN or infinite REAL is the string \"NaN\", \"Infinity\" or \"-Infinity\". storage_classes gives each row's " +
				"SQLite storage classes, one letter per column: i INTEGER, r REAL, t TEXT, b BLOB, n NULL (JSON writes a REAL " +
				"such as 2.0 as 2)."

		/** Said by the tools that return cells whose TEXT and BLOB values the server can cut. */
		private const val CUT_CELLS_NOTE =
			"truncated_cells counts the cut cells and truncated_cell_indexes lists each as [row, column], both from 0 and " +
				"the row relative to the rows returned; a TEXT cell has no marker of its own, and a cut BLOB is in the list too " +
				"(it also has truncated true)."

		/**
		 * Said by every tool that runs a statement. The limit itself is enforced in C (SQLITEDBG_SERVER_NON_WAL_MS in
		 * sqlitedbg.h, which says why); this text must follow it.
		 */
		const val NON_WAL_NOTE =
			"On a database that is not in WAL mode a statement is stopped after 1.5 s (best effort: it can run up to about 3 s " +
				"longer), so a long read does not block the app's own writes; use a narrower query or a LIMIT. A database in " +
				"WAL mode has no such limit (the $DEADLINE_SECONDS s limit applies, also best effort)."

		/** Server connections never load what the app registers on its own connections (see the README). */
		private const val APP_FUNCTIONS_NOTE =
			"Functions the app adds to its own connections, such as sqlite-js's stored JavaScript functions, do not exist " +
				"here (\"no such function\", also through a view)"

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
						.put("hex", hex(value))
						.put("bytes", value.size)
						.put("truncated", truncated)
				}

				else -> {
					value.toString()
				}
			}

		private val HEX_DIGITS = "0123456789abcdef".toCharArray()

		fun hex(bytes: ByteArray): String {
			val out = CharArray(bytes.size * 2)
			bytes.forEachIndexed { i, b ->
				out[i * 2] = HEX_DIGITS[(b.toInt() shr 4) and 0xf]
				out[i * 2 + 1] = HEX_DIGITS[b.toInt() and 0xf]
			}
			return String(out)
		}

		/** One letter per value: i INTEGER, r REAL, t TEXT, b BLOB, n NULL (see [VALUES_NOTE]). */
		fun storageClasses(values: List<Any?>): String =
			values.joinToString("") {
				when (it) {
					null -> "n"
					is Long, is Int -> "i"
					is Double -> "r"
					is ByteArray -> "b"
					else -> "t"
				}
			}

		fun encodeLong(value: Long): Any = if (value > MAX_SAFE_INTEGER || value < -MAX_SAFE_INTEGER) value.toString() else value

		fun quoteIdentifier(name: String) = "\"" + name.replace("\"", "\"\"") + "\""
	}

	private val lock = Any()
	private val serverConnections = HashMap<String, CachedConnection>()
	private var clientConnected = false
	private var rowCapture = false
	private var lastEventsCallMs = 0L
	private var cancelIdleCheck: (() -> Unit)? = null
	private var idleCheckGeneration = 0

	/** A server connection and the ids of the app connections to its path when it was opened. */
	private class CachedConnection(
		val connection: ServerConnection,
		val appConnectionIds: Set<Long>,
	)

	/** One tool call: its deadline, the POST's (or its own when none was set), and the time it has spent waiting for [lock]. */
	private inner class Call(
		postDeadline: Long?,
	) {
		val deadline = postDeadline ?: (clock() + DEADLINE_MS)
		var waitedMs = 0L

		fun <T> locked(block: () -> T): T {
			val before = clock()
			return synchronized(lock) {
				waitedMs += clock() - before
				block()
			}
		}
	}

	/** Closes the server connections and tells the engine no client is watching. Call when the server stops. */
	fun onServerStopped() {
		synchronized(lock) {
			serverConnections.values.forEach { runCatching { it.connection.close() } }
			serverConnections.clear()
			cancelPendingIdleCheck()
			if (clientConnected) {
				clientConnected = false
				engine.setClientConnected(false)
			}
			setRowCaptureLocked(false)
		}
	}

	/** Row recording costs the app time on every row it reads, so it is on only while a get_events client asks for rows. [lock] held. */
	private fun setRowCaptureLocked(on: Boolean) {
		if (on == rowCapture) return
		rowCapture = on
		if (on) engine.setRowCapture(EVENT_ROWS_PER_STATEMENT, EVENT_VALUE_BYTES, EVENT_ROW_BYTES) else engine.setRowCapture(0, 0, 0)
	}

	/**
	 * Stops recording bound values once [EVENTS_IDLE_MS] have passed since the last get_events call:
	 * expanding every statement costs memory, and nobody is reading. Checked on every tool call and
	 * by a timer, so it also happens when no call comes.
	 */
	fun expireIdleClient() = synchronized(lock) { expireIdleClientLocked() }

	private fun expireIdleClientLocked() {
		if (clientConnected && clock() - lastEventsCallMs >= EVENTS_IDLE_MS) {
			clientConnected = false
			engine.setClientConnected(false)
			setRowCaptureLocked(false)
		}
	}

	/** Cancels the pending check, and a check already running no longer reschedules. [lock] held. */
	private fun cancelPendingIdleCheck() {
		idleCheckGeneration++
		cancelIdleCheck?.invoke()
		cancelIdleCheck = null
	}

	/** At most one check is pending: each one cancels the one before it. [lock] held. */
	private fun scheduleIdleCheck() {
		cancelPendingIdleCheck()
		val generation = idleCheckGeneration
		cancelIdleCheck =
			idleScheduler.schedule(EVENTS_IDLE_MS / 4) {
				expireIdleClient()
				synchronized(lock) {
					if (clientConnected && idleCheckGeneration == generation) scheduleIdleCheck()
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
			) { _, _ -> listDatabases() },
			tool(
				"list_connections",
				"List connections",
				"Lists the app's open SQLite connections (not yet freed by SQLite), oldest first.",
			) { _, _ -> listConnections() },
			tool(
				"get_schema",
				"Get schema",
				"Returns the tables, indexes, views and triggers of a database, with their CREATE statements, from sqlite_master. " +
					"A CREATE statement is cut at ${MAX_SCHEMA_SQL_BYTES / 1024} KiB: its entry then has truncated true, and " +
					"truncated_cells counts the cut values (there is no truncated_cell_indexes: the entry says which). " +
					"The top-level truncated is true when anything was cut or entries were left " +
					"out: past the first $MAX_ROWS, or past about ${MAX_TOTAL_BYTES / (1024 * 1024)} MiB of cell bytes (about " +
					"${MAX_TOTAL_BYTES / MAX_SCHEMA_SQL_BYTES} statements of ${MAX_SCHEMA_SQL_BYTES / 1024} KiB). There is no offset: " +
					"to read the rest, page through sqlite_master with query (ORDER BY type, name, the order get_schema uses), " +
					"and read a statement longer than its $MAX_TEXT_BYTES-byte TEXT limit in parts with substr " +
					"(it counts characters, not bytes). $NON_WAL_NOTE",
				databaseSchema(),
			) { call, args -> getSchema(call, args) },
			tool(
				"query",
				"Run a query",
				"Runs one read-only SQL statement. Returns at most max_rows rows (default $DEFAULT_MAX_ROWS, at most $MAX_ROWS), " +
					"with each column's declared_type in column_types (null for an expression). $VALUES_NOTE " +
					"TEXT cells are cut at $MAX_TEXT_BYTES bytes and BLOBs at $MAX_BLOB_BYTES: $CUT_CELLS_NOTE " +
					"A result also stops at about " +
					"${MAX_TOTAL_BYTES / (1024 * 1024)} MiB of cell bytes (has_more is then true), though the first row always " +
					"comes back whole. Use offset to page. " +
					"$PAGING_NOTE Writes are refused. $APP_FUNCTIONS_NOTE: read the rows or compute in plain SQL instead. $NON_WAL_NOTE",
				databaseSchema(
					"sql" to prop("string", "The SQL statement."),
					"args" to argsSchema(),
					"offset" to prop("integer", "Rows to skip. Default 0."),
					"max_rows" to prop("integer", "Rows to return. Default $DEFAULT_MAX_ROWS, at most $MAX_ROWS."),
					required = listOf("sql"),
				),
			) { call, args -> query(call, args) },
			tool(
				"explain_query_plan",
				"Explain a query plan",
				"Runs EXPLAIN QUERY PLAN on a SQL statement and returns SQLite's plan. The statement is not run. " +
					"A cell cut at $MAX_TEXT_BYTES bytes is listed as in query: $CUT_CELLS_NOTE $APP_FUNCTIONS_NOTE. $NON_WAL_NOTE",
				databaseSchema(
					"sql" to prop("string", "The SQL statement."),
					"args" to argsSchema(),
					required = listOf("sql"),
				),
			) { call, args -> explain(call, args) },
			tool(
				"get_stats",
				"Get statistics",
				"Returns page_count, page_size, freelist_count, and the row count of each table when the database has fewer than " +
					"$MAX_TABLES_FOR_COUNTS tables. Also what the sqlite3 shell's .dbinfo shows: schema_version, user_version, " +
					"application_id, encoding, auto_vacuum, journal_mode, schema_objects (counts by type) and schema_size (bytes " +
					"of CREATE statements), and header: the header fields no PRAGMA reads (write_format, read_format, " +
					"reserved_bytes, file_change_counter, schema_format, default_cache_size, autovacuum_top_root, " +
					"incremental_vacuum, software_version), when SQLite has sqlite_dbpage (else header_unavailable). $NON_WAL_NOTE",
				databaseSchema(),
			) { call, args -> getStats(call, args) },
			tool(
				"integrity_check",
				"Check integrity",
				"Runs PRAGMA quick_check, or the slower PRAGMA integrity_check when full is true. " +
					"ok is true when SQLite reports no problem. A message cut at $MAX_TEXT_BYTES bytes is listed as in query " +
					"(truncated_cells, truncated_cell_indexes: [message index, 0]). " +
					"Both checks fail with \"unknown function\" when a CHECK constraint or a " +
					"NOT NULL virtual generated column calls a function the app adds to its own connections. A full integrity_check " +
					"also fails on an index on such an expression and on a partial index whose WHERE calls one; " +
					"quick_check does not fail on those two, so use it then. " +
					"Views, triggers and other generated columns do not make either fail. $NON_WAL_NOTE " +
					"A full integrity_check of a big database that is not in WAL mode will stop early with that error.",
				databaseSchema("full" to prop("boolean", "Run the full integrity_check. Default false."), required = emptyList()),
			) { call, args -> integrityCheck(call, args) },
			tool(
				"suggest_indexes",
				"Suggest indexes",
				"Suggests indexes for one or more SQL statements, as the sqlite3 shell's .expert does (SQLite's ext/expert). " +
					"The statements are prepared, never run. By default (sample 0) no row of the app's data is read: the " +
					"suggestions rest on SQLite's default estimates, which assume large tables, so on a small table they can " +
					"name an index that would not help; check them with explain_query_plan. sample (1 to 100) reads that " +
					"percentage of the rows of each table with a candidate index first, for estimates from the data: it costs " +
					"a scan of those tables in the app, and on a database that is not in WAL mode it stops after 1.5 s (use " +
					"a lower sample, or 0). For each statement, indexes lists the CREATE INDEX statements that would help " +
					"(empty when the existing ones suffice) and plan the query plan with them; candidates lists every index " +
					"weighed. Nothing is created. $APP_FUNCTIONS_NOTE.",
				databaseSchema(
					"sql" to prop("string", "One or more SQL statements."),
					"sample" to prop("integer", "Percentage of rows to read for the estimates, 0 to 100. Default 0: none."),
					required = listOf("sql"),
				),
			) { call, args -> suggestIndexes(call, args) },
			tool(
				"lint_foreign_keys",
				"Check foreign key indexes",
				"Lists each foreign key and whether an index on the child table covers it, as the sqlite3 shell's " +
					".lint fkey-indexes does: without one, deleting or updating a parent row scans the child table. An " +
					"uncovered key has create_index, a statement that would cover it (not run). Unlike sqlite3, a child column " +
					"whose collation differs from its parent key's is not taken into account. truncated is true past the first " +
					"$MAX_ROWS foreign key columns. $NON_WAL_NOTE",
				databaseSchema(),
			) { call, args -> lintForeignKeys(call, args) },
			tool(
				"dump",
				"Dump as SQL",
				"Returns a database, or one table with its indexes and triggers, as SQL text that rebuilds it, as the sqlite3 " +
					"shell's .dump does: PRAGMA foreign_keys=OFF, BEGIN TRANSACTION, each table's CREATE and INSERTs, then the " +
					"indexes, triggers and views, then COMMIT. A page holds at most $MAX_ROWS statements and about " +
					"${MAX_TOTAL_BYTES / (1024 * 1024)} MiB; pass next_cursor as cursor for the next, until complete is true. " +
					"A statement longer than ${DUMP_STATEMENT_BYTES / (1024 * 1024)} MiB is cut and listed in " +
					"truncated_statements (indexes into sql), and the dump is then not whole. Paging re-reads the schema and " +
					"the rows, so a dump of a database that changes between pages can miss or repeat rows. $NON_WAL_NOTE",
				databaseSchema(
					"table" to prop("string", "Only this table, with its indexes and triggers. Default: the whole database."),
					"cursor" to prop("string", "The next_cursor of the previous page. Default: the start."),
				),
			) { call, args -> dump(call, args) },
			tool(
				"export",
				"Export a query",
				"Runs one read-only SQL statement and returns its rows as CSV or SQL INSERT statements, as the sqlite3 shell's " +
					".mode csv and .mode insert do. CSV (RFC 4180, CRLF line ends): a header row on the first page unless " +
					"header is false, NULL as an empty field, the empty string as \"\", a BLOB as hex. insert: one " +
					"INSERT INTO table VALUES(...) per row, with literals that read back the same (X'..' for a BLOB). At most " +
					"max_rows rows (default $DEFAULT_MAX_ROWS, at most $MAX_ROWS) and about ${MAX_TOTAL_BYTES / (1024 * 1024)} " +
					"MiB of values per page; use offset to page ($PAGING_NOTE) A TEXT or BLOB value longer than " +
					"${EXPORT_VALUE_BYTES / 1024} KiB is cut, counted in truncated_cells, and the export is then not whole. " +
					"$APP_FUNCTIONS_NOTE. $NON_WAL_NOTE",
				databaseSchema(
					"sql" to prop("string", "The SQL statement."),
					"args" to argsSchema(),
					"format" to
						JSONObject()
							.put("type", "string")
							.put("enum", JSONArray(listOf("csv", "insert")))
							.put("description", "csv or insert."),
					"table" to prop("string", "insert: the table name in the statements. Default \"table\"."),
					"header" to prop("boolean", "csv: a header row on the first page (offset 0). Default true."),
					"offset" to prop("integer", "Rows to skip. Default 0."),
					"max_rows" to prop("integer", "Rows to return. Default $DEFAULT_MAX_ROWS, at most $MAX_ROWS."),
					required = listOf("sql", "format"),
				),
			) { call, args -> export(call, args) },
			tool(
				"get_events",
				"Get SQL events",
				"Returns the SQL the app ran, oldest first, after the cursor. Pass the returned next as after and the returned run " +
					"as run to continue; dropped counts events that left the buffer unread. reset true means the app restarted " +
					"(run differs, or after is past the newest event), and events are read from the start. " +
					"The first call starts recording bound values into the SQL; " +
					"that stops ${EVENTS_IDLE_MS / 1000} s after the last get_events call, so call it at least that often while " +
					"watching. " +
					"kind is statement (one starts; a trigger's SQL starts with --), profile (one finished: duration_nanos; rows, the " +
					"rows it returned; changes, the rows it changed itself, not through triggers, null for a read; null when the " +
					"statement's start was not seen), close, or row. With include_rows true (the default), the app's statements " +
					"after the call also record their first $EVENT_ROWS_PER_STATEMENT rows as row events: row_number (from 1), " +
					"column_count, values, storage_classes and cut_values (indexes of TEXT and BLOB values cut at " +
					"$EVENT_VALUE_BYTES bytes); a row's data stops at ${EVENT_ROW_BYTES / 1024} KiB, and then values holds fewer " +
					"than column_count and truncated is true. A profile's rows_cut says rows went unrecorded past the limit. " +
					"$VALUES_NOTE Recording rows costs the app a few microseconds a row, so pass include_rows false when you need " +
					"only the SQL; it then stops, and row events are left out.",
				JSONObject()
					.put("type", "object")
					.put("additionalProperties", false)
					.put(
						"properties",
						JSONObject()
							.put("after", prop("integer", "Cursor: the next from the last call. Default 0."))
							.put(
								"run",
								prop("string", "The run from the last call. If it differs from the current, the read starts from 0."),
							).put("include_rows", prop("boolean", "Record and return rows the app's statements return. Default true.")),
					).put("required", JSONArray()),
			) { _, args -> getEvents(args) },
		)

	private fun tool(
		name: String,
		title: String,
		description: String,
		inputSchema: JSONObject = JSONObject().put("type", "object").put("properties", JSONObject()).put("additionalProperties", false),
		run: (Call, JSONObject) -> JSONObject,
	) = McpTool(name, title, description, inputSchema, readOnly = true, run = { args, deadline ->
		val call = Call(deadline)
		call.locked { expireIdleClientLocked() }
		run(call, args)
	})

	private fun prop(
		type: String,
		description: String,
	) = JSONObject().put("type", type).put("description", description)

	private fun argsSchema() =
		JSONObject()
			.put("type", JSONArray(listOf("array", "object")))
			.put(
				"description",
				"Values to bind: null, numbers or strings. An array binds by position (? and ?NNN, by number); an object binds " +
					"by name (:name, @name, \$name, ?NNN), with keys written as in the SQL. A key without a prefix, such as id, " +
					"matches :id, @id and \$id; a ?NNN key keeps its ?. Every parameter needs a value, and every key a parameter.",
			)

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

	private fun canonical(path: String): String = runCatching { File(path).canonicalPath }.getOrDefault(path)

	private fun listDatabases(): JSONObject {
		val connections = engine.connections()
		val byPath = LinkedHashMap<String, MutableList<AppConnection>>()
		for (c in connections) if (c.path.isNotEmpty()) byPath.getOrPut(c.path) { mutableListOf() } += c
		// The folder may be reached through a link (/data/user/0/<pkg> and /data/data/<pkg>), so match resolved paths.
		val openCanonical = byPath.keys.map { canonical(it) }.toSet()
		val list = JSONArray()
		for ((path, conns) in byPath) {
			// Same rule as runChecked: a path whose app connections are all closing is not available.
			list.put(JSONObject().put("path", path).put("available", conns.any { !it.closing }).put("connections", conns.size))
		}
		databaseDir?.listFiles()?.sortedBy { it.name }?.forEach { f ->
			if (!f.isFile || SIDE_FILE_SUFFIXES.any { f.name.endsWith(it) }) return@forEach
			if (canonical(f.absolutePath) in openCanonical) return@forEach
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

	private fun getSchema(
		call: Call,
		args: JSONObject,
	): JSONObject {
		val result =
			run(
				call,
				args,
				"SELECT type, name, tbl_name, sql FROM sqlite_master ORDER BY type, name",
				Bind.NONE,
				0,
				QueryLimits(MAX_ROWS, MAX_SCHEMA_SQL_BYTES, MAX_BLOB_BYTES, MAX_TOTAL_BYTES),
			)
		val groups = linkedMapOf("table" to JSONArray(), "index" to JSONArray(), "view" to JSONArray(), "trigger" to JSONArray())
		result.rows.forEachIndexed { r, row ->
			val item =
				JSONObject()
					.put("name", encodeValue(row[1]))
					.put("table", encodeValue(row[2]))
					.put("sql", encodeValue(row[3]))
					.put("truncated", row.indices.any { result.isTruncated(r, it) })
			groups[row[0] as? String]?.put(item)
		}
		return JSONObject()
			.put("tables", groups["table"])
			.put("indexes", groups["index"])
			.put("views", groups["view"])
			.put("triggers", groups["trigger"])
			.put("truncated", result.hasMore || result.truncatedCells > 0)
			.put("truncated_cells", result.truncatedCells)
	}

	private fun query(
		call: Call,
		args: JSONObject,
	): JSONObject {
		val sql = stringArg(args, "sql")
		val offset = intArg(args, "offset", 0, 0, Long.MAX_VALUE)
		val maxRows = intArg(args, "max_rows", DEFAULT_MAX_ROWS.toLong(), 1, MAX_ROWS.toLong()).toInt()
		val result = run(call, args, sql, bindArgs(args), offset, QueryLimits(maxRows, MAX_TEXT_BYTES, MAX_BLOB_BYTES, MAX_TOTAL_BYTES))
		val out = encodeResult(result, offset)
		out.put("note", PAGING_NOTE)
		return out
	}

	private fun explain(
		call: Call,
		args: JSONObject,
	): JSONObject {
		val result =
			run(
				call,
				args,
				"EXPLAIN QUERY PLAN " + stringArg(args, "sql"),
				bindArgs(args),
				0,
				QueryLimits(MAX_ROWS, MAX_TEXT_BYTES, MAX_BLOB_BYTES, MAX_TOTAL_BYTES),
			)
		return JSONObject()
			.put("plan", rowsAsObjects(result))
			.put("truncated_cells", result.truncatedCells)
			.put("truncated_cell_indexes", truncatedCellIndexes(result))
	}

	private fun getStats(
		call: Call,
		args: JSONObject,
	): JSONObject {
		val limits = QueryLimits(MAX_ROWS, MAX_TEXT_BYTES, MAX_BLOB_BYTES, MAX_TOTAL_BYTES)
		val path = checkedPath(call, args)

		fun pragma(name: String) = runChecked(call, path, "PRAGMA $name", Bind.NONE, 0, limits).rows.firstOrNull()?.firstOrNull()
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
				call,
				path,
				"SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite\\_%' ESCAPE '\\' ORDER BY name",
				Bind.NONE,
				0,
				limits,
			).rows
				.map { it[0] as String }
		if (tables.size < MAX_TABLES_FOR_COUNTS) {
			val counts = JSONObject()
			for (t in tables) {
				val n =
					try {
						runChecked(
							call,
							path,
							"SELECT COUNT(*) FROM ${quoteIdentifier(t)}",
							Bind.NONE,
							0,
							limits,
						).rows.firstOrNull()?.firstOrNull()
					} catch (e: McpToolError) {
						e.message
					}
				counts.put(t, encodeValue(n))
			}
			out.put("row_counts", counts)
		} else {
			// The list above stops at MAX_ROWS, so a full one is a lower bound.
			val count = if (tables.size >= MAX_ROWS) "at least ${tables.size}" else "${tables.size}"
			out.put("row_counts_skipped", "The database has $count tables; counts are given only under $MAX_TABLES_FOR_COUNTS.")
		}
		addDbinfo(call, path, out, limits)
		return out
	}

	/**
	 * What sqlite3's .dbinfo adds: PRAGMA values, schema object counts and size, and the header fields no PRAGMA
	 * reads, from page 1 through sqlite_dbpage when SQLite was built with it. Never from the file itself: closing
	 * any descriptor to it would drop the app's POSIX locks on it.
	 */
	private fun addDbinfo(
		call: Call,
		path: String,
		out: JSONObject,
		limits: QueryLimits,
	) {
		fun pragma(name: String) = runChecked(call, path, "PRAGMA $name", Bind.NONE, 0, limits).rows.firstOrNull()?.firstOrNull()
		for (name in listOf("schema_version", "user_version", "application_id", "encoding", "auto_vacuum", "journal_mode")) {
			out.put(name, encodeValue(pragma(name)))
		}
		val objects =
			JSONObject()
				.put("table", 0)
				.put("index", 0)
				.put("trigger", 0)
				.put("view", 0)
		var schemaSize = 0L
		for (
		row in runChecked(
			call,
			path,
			"SELECT type, count(*), total(length(sql)) FROM sqlite_master GROUP BY type",
			Bind.NONE,
			0,
			limits,
		).rows
		) {
			objects.put(row[0] as String, row[1] as Long)
			schemaSize += (row[2] as Double).toLong()
		}
		out.put("schema_objects", objects).put("schema_size", schemaSize)
		val page =
			try {
				runChecked(call, path, "SELECT data FROM sqlite_dbpage WHERE pgno = 1", Bind.NONE, 0, limits)
					.rows
					.firstOrNull()
					?.firstOrNull()
			} catch (e: McpToolError) {
				out.put("header_unavailable", "SQLite here has no sqlite_dbpage (${e.message}), so the header fields are left out.")
				return
			}
		val fields = (page as? ByteArray)?.let { ShellFormat.headerFields(it) }
		if (fields == null) {
			out.put("header_unavailable", "Page 1 could not be read.")
			return
		}
		out.put("header", JSONObject().also { h -> fields.forEach { (k, v) -> h.put(k, v) } })
	}

	private fun integrityCheck(
		call: Call,
		args: JSONObject,
	): JSONObject {
		val full =
			when (val v = args.opt("full")) {
				null, JSONObject.NULL -> false
				is Boolean -> v
				else -> throw McpToolError("full must be true or false.")
			}
		val pragma = if (full) "PRAGMA integrity_check" else "PRAGMA quick_check"
		val result = run(call, args, pragma, Bind.NONE, 0, QueryLimits(MAX_ROWS, MAX_TEXT_BYTES, MAX_BLOB_BYTES, MAX_TOTAL_BYTES))
		val messages = result.rows.map { it.firstOrNull()?.toString() ?: "" }
		return JSONObject()
			.put("check", if (full) "integrity_check" else "quick_check")
			.put("ok", messages == listOf("ok"))
			.put("messages", JSONArray(messages))
			.put("truncated_cells", result.truncatedCells)
			.put("truncated_cell_indexes", truncatedCellIndexes(result))
	}

	private fun suggestIndexes(
		call: Call,
		args: JSONObject,
	): JSONObject {
		val sql = stringArg(args, "sql")
		val sample = intArg(args, "sample", 0, 0, 100).toInt()
		val path = checkedPath(call, args)
		val result = onConnection(call, path) { it.suggestIndexes(sql, sample, call.deadline) }

		fun lines(text: String?) = JSONArray(text?.lines()?.map { it.trimEnd() }?.filter { it.isNotEmpty() } ?: emptyList<String>())
		val statements = JSONArray()
		for (st in result.statements) {
			statements.put(JSONObject().put("sql", st.sql).put("indexes", lines(st.indexes)).put("plan", lines(st.plan)))
		}
		return JSONObject().put("statements", statements).put("candidates", lines(result.candidates))
	}

	private class ForeignKey(
		val child: String,
		val parent: String,
	) {
		val from = mutableListOf<String>()
		val to = mutableListOf<String?>()
	}

	private fun lintForeignKeys(
		call: Call,
		args: JSONObject,
	): JSONObject {
		val path = checkedPath(call, args)
		val limits = QueryLimits(MAX_ROWS, MAX_TEXT_BYTES, MAX_BLOB_BYTES, MAX_TOTAL_BYTES)
		val list =
			runChecked(
				call,
				path,
				"SELECT s.name, f.id, f.\"table\", f.\"from\", f.\"to\" FROM sqlite_master AS s, " +
					"pragma_foreign_key_list(s.name) AS f WHERE s.type = 'table' ORDER BY s.name, f.id, f.seq",
				Bind.NONE,
				0,
				limits,
			)
		val keys = LinkedHashMap<Pair<String, Any?>, ForeignKey>()
		for (row in list.rows) {
			val key = keys.getOrPut((row[0] as String) to row[1]) { ForeignKey(row[0] as String, row[2] as String) }
			key.from += row[3] as String
			key.to += row[4] as String?
		}
		val out = JSONArray()
		var uncovered = 0
		for (key in keys.values) {
			// A key naming no parent columns refers to the parent's primary key, as in the shell's LEFT JOIN on pragma_table_info.
			val parentColumns =
				if (key.to.all { it != null }) {
					key.to.map { it!! }
				} else {
					runChecked(
						call,
						path,
						"SELECT name FROM pragma_table_info(?) WHERE pk > 0 ORDER BY pk",
						Bind(listOf(key.parent), null),
						0,
						limits,
					).rows
						.map { it[0] as String }
				}
			val child = quoteIdentifier(key.child)
			val plan =
				runChecked(
					call,
					path,
					"EXPLAIN QUERY PLAN SELECT 1 FROM $child WHERE " + key.from.joinToString(" AND ") { "$child.${quoteIdentifier(it)}=?" },
					Bind(List(key.from.size) { null }, null),
					0,
					limits,
				).rows.firstOrNull()?.getOrNull(3) as? String ?: ""
			// The shell's two patterns (lintFkeyIndexes in shell.c.in)
			val covered =
				ShellFormat.globMatches(
					"SEARCH ${key.child} USING COVERING INDEX*(" + key.from.joinToString(" AND ") { "*=?" } + ")",
					plan,
				) ||
					ShellFormat.globMatches("SEARCH * USING INTEGER PRIMARY KEY (rowid=?)", plan)
			val item =
				JSONObject()
					.put("child_table", key.child)
					.put("child_columns", JSONArray(key.from))
					.put("parent_table", key.parent)
					.put("parent_columns", JSONArray(parentColumns))
					.put("covered", covered)
			if (!covered) {
				uncovered++
				item.put(
					"create_index",
					"CREATE INDEX ${quoteIdentifier(key.child + "_" + key.from.joinToString("_"))} ON $child(" +
						key.from.joinToString(", ") { quoteIdentifier(it) } + ");",
				)
			}
			out.put(item)
		}
		return JSONObject().put("foreign_keys", out).put("uncovered", uncovered).put("truncated", list.hasMore)
	}

	/** One step of a dump: statements known up front, or a table's rows as INSERTs (see [dump]). */
	private sealed class DumpUnit {
		class Fixed(
			val statements: List<String>,
			/** Indexes into [statements] of those cut at the statement limit. */
			val cut: Set<Int> = emptySet(),
		) : DumpUnit()

		class Rows(
			val table: String,
		) : DumpUnit()
	}

	/**
	 * sqlite3's .dump: the plan is rebuilt from the schema on every page, and the cursor is "unit.offset", the
	 * statement or row to start from in that step.
	 */
	private fun dump(
		call: Call,
		args: JSONObject,
	): JSONObject {
		val path = checkedPath(call, args)
		val only =
			when (val v = args.opt("table")) {
				null, JSONObject.NULL -> null
				is String -> v
				else -> throw McpToolError("table must be a string.")
			}
		val cursor =
			when (val v = args.opt("cursor")) {
				null, JSONObject.NULL -> {
					0 to 0L
				}

				is String -> {
					val parts = v.split('.')
					val unit = parts.getOrNull(0)?.toIntOrNull()
					val offset = parts.getOrNull(1)?.toLongOrNull()
					if (parts.size != 2 || unit == null || offset == null || unit < 0 || offset < 0) {
						throw McpToolError("cursor must be a next_cursor from dump.")
					}
					unit to offset
				}

				else -> {
					throw McpToolError("cursor must be a string.")
				}
			}
		val limits = QueryLimits(MAX_ROWS, MAX_TEXT_BYTES, MAX_BLOB_BYTES, MAX_TOTAL_BYTES)
		val schemaLimits = QueryLimits(MAX_ROWS, DUMP_STATEMENT_BYTES, MAX_BLOB_BYTES, MAX_TOTAL_BYTES)

		fun tablesOf(names: List<String>?): QueryResult {
			val filter = if (names != null) " AND name IN (${names.joinToString(",") { "?" }})" else ""
			return runChecked(
				call,
				path,
				"SELECT name, sql FROM sqlite_master WHERE type = 'table' AND sql NOT NULL$filter ORDER BY name = 'sqlite_sequence', rowid",
				if (names != null) Bind(names, null) else Bind.NONE,
				0,
				schemaLimits,
			)
		}

		val tables = tablesOf(only?.let { listOf(it) })
		if (tables.hasMore) {
			throw McpToolError(
				if (tables.rows.size < MAX_ROWS) {
					"The CREATE statements of the tables come to more than ${MAX_TOTAL_BYTES / (1024 * 1024)} MiB; " +
						"dump them one at a time with table."
				} else {
					"The database has more than $MAX_ROWS tables; dump them one at a time with table."
				},
			)
		}
		if (only != null && tables.rows.isEmpty()) throw McpToolError("No table named \"$only\".")
		val named = only?.let { listOf(it) }
		val units = mutableListOf<DumpUnit>(DumpUnit.Fixed(listOf("PRAGMA foreign_keys=OFF;", "BEGIN TRANSACTION;")))
		var writableSchema = false
		for ((r, row) in tables.rows.withIndex()) {
			val name = row[0] as String
			val sql = row[1] as String
			val cutSql = tables.isTruncated(r, 1)
			when {
				name == "sqlite_sequence" -> {
					units += DumpUnit.Fixed(listOf("DELETE FROM sqlite_sequence;"))
					units += DumpUnit.Rows(name)
				}

				name == "sqlite_stat1" -> {
					units += DumpUnit.Fixed(listOf("ANALYZE sqlite_schema;"))
					units += DumpUnit.Rows(name)
				}

				name.startsWith("sqlite_") -> {}

				isVirtualTable(sql) -> {
					// As the shell does: its shadow tables are dumped as tables, so it must not be created again.
					val statements = mutableListOf<String>()
					if (!writableSchema) statements += "PRAGMA writable_schema=ON;"
					writableSchema = true
					statements +=
						"INSERT INTO sqlite_schema(type,name,tbl_name,rootpage,sql) VALUES('table'," +
						"${ShellFormat.sqlLiteral(name)},${ShellFormat.sqlLiteral(name)},0,${ShellFormat.sqlLiteral(sql)});"
					units += DumpUnit.Fixed(statements, if (cutSql) setOf(statements.lastIndex) else emptySet())
				}

				else -> {
					units += DumpUnit.Fixed(listOf("$sql;"), if (cutSql) setOf(0) else emptySet())
					units += DumpUnit.Rows(name)
				}
			}
		}
		val others =
			runChecked(
				call,
				path,
				"SELECT sql FROM sqlite_master WHERE sql NOT NULL AND type IN ('index', 'trigger', 'view')" +
					(if (named != null) " AND tbl_name IN (${named.joinToString(",") { "?" }})" else "") + " ORDER BY rowid",
				if (named != null) Bind(named, null) else Bind.NONE,
				0,
				schemaLimits,
			)
		if (others.hasMore) {
			throw McpToolError(
				if (others.rows.size < MAX_ROWS) {
					"The CREATE statements of the indexes, triggers and views come to more than ${MAX_TOTAL_BYTES / (1024 * 1024)} MiB; " +
						"dump one table at a time."
				} else {
					"The database has more than $MAX_ROWS indexes, triggers and views; dump one table at a time."
				},
			)
		}
		val tail = others.rows.map { "${it[0]};" }.toMutableList()
		val tailCut =
			others.rows.indices
				.filter { others.isTruncated(it, 0) }
				.toMutableSet()
		if (writableSchema) tail += "PRAGMA writable_schema=OFF;"
		tail += "COMMIT;"
		units += DumpUnit.Fixed(tail, tailCut)

		val out = JSONArray()
		val cut = JSONArray()
		var bytes = 0L
		var unit = cursor.first
		var offset = cursor.second
		while (unit < units.size && out.length() < MAX_ROWS && bytes < MAX_TOTAL_BYTES) {
			when (val u = units[unit]) {
				is DumpUnit.Fixed -> {
					while (offset < u.statements.size && out.length() < MAX_ROWS && bytes < MAX_TOTAL_BYTES) {
						val st = u.statements[offset.toInt()]
						if (offset.toInt() in u.cut) cut.put(out.length())
						out.put(st)
						bytes += st.length
						offset++
					}
					if (offset < u.statements.size) break
				}

				is DumpUnit.Rows -> {
					val rows =
						runChecked(
							call,
							path,
							insertsFor(call, path, u.table, limits),
							Bind.NONE,
							offset,
							QueryLimits(MAX_ROWS - out.length(), DUMP_STATEMENT_BYTES, MAX_BLOB_BYTES, (MAX_TOTAL_BYTES - bytes).toInt()),
						)
					rows.rows.forEachIndexed { r, row ->
						if (rows.isTruncated(r, 0)) cut.put(out.length())
						val st = row[0] as? String ?: ""
						out.put(st)
						bytes += st.length
					}
					offset += rows.rows.size
					if (rows.hasMore) break
				}
			}
			unit++
			offset = 0
		}
		val complete = unit >= units.size
		return JSONObject()
			.put("sql", out)
			.put("complete", complete)
			.put("next_cursor", if (complete) JSONObject.NULL else "$unit.$offset")
			.put("truncated_statements", cut)
	}

	private fun isVirtualTable(createSql: String) = createSql.regionMatches(0, "CREATE VIRTUAL TABLE", 0, 20, ignoreCase = true)

	/**
	 * The SELECT that writes each row of [table] as an INSERT statement, in rowid order for a rowid table
	 * (so paging is stable). quote() writes literals that read back the same (a REAL that would not, with
	 * %!.15g, gets more digits). Generated columns are left out, with a column list, as the shell does.
	 */
	private fun insertsFor(
		call: Call,
		path: String,
		table: String,
		limits: QueryLimits,
	): String {
		val all = runChecked(call, path, "SELECT name, hidden FROM pragma_table_xinfo(?)", Bind(listOf(table), null), 0, limits).rows
		// hidden 2 and 3: a virtual and a stored generated column
		val columns = all.filter { it[1] != 2L && it[1] != 3L }.map { it[0] as String }
		val withoutRowid =
			runChecked(
				call,
				path,
				"SELECT wr FROM pragma_table_list WHERE schema = 'main' AND name = ?",
				Bind(listOf(table), null),
				0,
				limits,
			).rows
				.firstOrNull()
				?.firstOrNull() == 1L
		val target =
			quoteIdentifier(table) +
				if (columns.size < all.size) {
					"(" + columns.joinToString(",") { quoteIdentifier(it) } + ")"
				} else {
					""
				}
		val values = columns.joinToString(" || ',' || ") { "quote(${quoteIdentifier(it)})" }
		return "SELECT ${ShellFormat.sqlLiteral("INSERT INTO $target VALUES(")} || $values || ');' FROM ${quoteIdentifier(table)}" +
			if (withoutRowid) "" else " ORDER BY _rowid_"
	}

	private fun export(
		call: Call,
		args: JSONObject,
	): JSONObject {
		val sql = stringArg(args, "sql")
		val format = stringArg(args, "format")
		if (format != "csv" && format != "insert") throw McpToolError("format must be csv or insert.")
		val table =
			when (val v = args.opt("table")) {
				null, JSONObject.NULL -> "table"
				is String -> v
				else -> throw McpToolError("table must be a string.")
			}
		val header =
			when (val v = args.opt("header")) {
				null, JSONObject.NULL -> true
				is Boolean -> v
				else -> throw McpToolError("header must be true or false.")
			}
		val offset = intArg(args, "offset", 0, 0, Long.MAX_VALUE)
		val maxRows = intArg(args, "max_rows", DEFAULT_MAX_ROWS.toLong(), 1, MAX_ROWS.toLong()).toInt()
		val result =
			run(
				call,
				args,
				sql,
				bindArgs(args),
				offset,
				QueryLimits(maxRows, EXPORT_VALUE_BYTES, EXPORT_VALUE_BYTES, MAX_TOTAL_BYTES),
			)
		val text = StringBuilder()
		if (format == "csv") {
			if (header && offset == 0L) text.append(result.columns.joinToString(",") { ShellFormat.csvField(it) }).append("\r\n")
			for (row in result.rows) text.append(row.joinToString(",") { ShellFormat.csvField(it) }).append("\r\n")
		} else {
			for (row in result.rows) {
				text.append("INSERT INTO ").append(quoteIdentifier(table)).append(" VALUES(")
				text.append(row.joinToString(",") { ShellFormat.sqlLiteral(it) }).append(");\n")
			}
		}
		val out =
			JSONObject()
				.put("text", text.toString())
				.put("row_count", result.rows.size)
				.put("offset", encodeLong(offset))
				.put("has_more", result.hasMore)
				.put("truncated_cells", result.truncatedCells)
		if (result.hasMore) out.put("next_offset", encodeLong(offset + result.rows.size))
		return out
	}

	private fun getEvents(args: JSONObject): JSONObject {
		val after = intArg(args, "after", 0, 0, Long.MAX_VALUE)
		val run =
			when (val v = args.opt("run")) {
				null, JSONObject.NULL -> null
				is String -> v
				else -> throw McpToolError("run must be a string.")
			}
		val includeRows =
			when (val v = args.opt("include_rows")) {
				null, JSONObject.NULL -> true
				is Boolean -> v
				else -> throw McpToolError("include_rows must be true or false.")
			}
		val otherRun = run != null && run != engine.runId
		synchronized(lock) {
			lastEventsCallMs = clock()
			setRowCaptureLocked(includeRows)
			if (!clientConnected) {
				engine.setClientConnected(true)
				clientConnected = true
				scheduleIdleCheck()
			}
		}
		val batch =
			try {
				engine.readEvents(if (otherRun) 0 else after, EVENT_BATCH_BYTES)
			} catch (e: DebugEngineException) {
				throw engineError(e)
			}
		val events = JSONArray()
		for (e in batch.events) {
			if (e.kind == EventKind.ROW && !includeRows) continue
			events.put(encodeEvent(e))
		}
		return JSONObject()
			.put("run", engine.runId)
			.put("reset", otherRun || batch.reset)
			.put("next", encodeLong(batch.next))
			.put("dropped", encodeLong(batch.dropped))
			.put("events", events)
	}

	private fun encodeEvent(e: Event): JSONObject {
		val out =
			JSONObject()
				.put("seq", encodeLong(e.seq))
				.put("kind", e.kind.name.lowercase())
				.put("connection_id", encodeLong(e.connectionId))
				.put("thread_id", encodeLong(e.threadId))
				.put("time_epoch_nanos", encodeLong(e.timeEpochNanos))
				.put("duration_nanos", e.durationNanos?.let { encodeLong(it) } ?: JSONObject.NULL)
				.put("sql", e.sql)
				.put("truncated", e.truncated)
				.put("expanded", e.expanded)
		when (e.kind) {
			EventKind.PROFILE -> {
				out
					.put("rows", e.rows?.let { encodeLong(it) } ?: JSONObject.NULL)
					.put("changes", e.changes?.let { encodeLong(it) } ?: JSONObject.NULL)
					.put("rows_cut", e.rowsCut)
			}

			EventKind.ROW -> {
				val row = e.row
				out.put("row_number", e.rows?.let { encodeLong(it) } ?: JSONObject.NULL)
				if (row != null) {
					out
						.put("column_count", row.columnCount)
						.put("values", JSONArray(row.values.mapIndexed { i, v -> encodeValue(v, i in row.cutIndexes) }))
						.put("storage_classes", storageClasses(row.values))
						.put("cut_values", JSONArray(row.cutIndexes.sorted()))
				}
			}

			else -> {}
		}
		return out
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
				.put("column_types", JSONArray().also { a -> result.declaredTypes.forEach { a.put(it ?: JSONObject.NULL) } })
				.put("rows", rows)
				.put("storage_classes", JSONArray(result.rows.map { storageClasses(it) }))
				.put("row_count", result.rows.size)
				.put("offset", encodeLong(offset))
				.put("has_more", result.hasMore)
				.put("truncated_cells", result.truncatedCells)
				.put("truncated_cell_indexes", truncatedCellIndexes(result))
		if (result.hasMore) out.put("next_offset", encodeLong(offset + result.rows.size))
		return out
	}

	/** The cut cells as `[row, column]` pairs, the row relative to the rows in [result]. */
	private fun truncatedCellIndexes(result: QueryResult): JSONArray {
		val out = JSONArray()
		val columns = result.columns.size.toLong()
		if (columns == 0L) return out
		for (i in result.truncatedCellIndexes.sorted()) out.put(JSONArray().put((i / columns).toInt()).put((i % columns).toInt()))
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

	private fun checkedPath(
		call: Call,
		args: JSONObject,
	): String {
		val path = stringArg(args, "database")
		val known = knownPaths(engine.connections())
		call.locked { dropStaleServerConnections(engine.connections()) }
		if (path !in known) throw unknownDatabase(path, known)
		return path
	}

	private fun knownPaths(live: List<AppConnection>) =
		live
			.filter { !it.closing }
			.map { it.path }
			.filter { it.isNotEmpty() }
			.distinct()

	private fun unknownDatabase(
		path: String,
		known: List<String>,
	) = McpToolError(
		"Unknown database \"$path\": use a path from list_databases that is available.",
		JSONObject().put("available", JSONArray(known)),
	)

	private fun run(
		call: Call,
		args: JSONObject,
		sql: String,
		bind: Bind,
		offset: Long,
		limits: QueryLimits,
	): QueryResult = runChecked(call, checkedPath(call, args), sql, bind, offset, limits)

	private fun runChecked(
		call: Call,
		path: String,
		sql: String,
		bind: Bind,
		offset: Long,
		limits: QueryLimits,
	): QueryResult = onConnection(call, path) { it.query(sql, bind.values, bind.names, offset, limits, call.deadline) }

	/** Runs [block] on the cached server connection to [path], opening it if needed, under [lock]. */
	private fun <T> onConnection(
		call: Call,
		path: String,
		block: (ServerConnection) -> T,
	): T =
		call.locked {
			try {
				val live = engine.connections()
				dropStaleServerConnections(live)
				val conn =
					serverConnections
						.getOrPut(path) {
							// checkedPath looked at an earlier snapshot: the app may have closed, or begun closing, its
							// last connection to this path since, and the server never opens a database the app doesn't
							// have open. knownPaths skips closing connections too; this re-check covers the gap since its snapshot.
							val ids = live.filter { it.path == path && !it.closing }.map { it.id }.toSet()
							if (ids.isEmpty()) throw unknownDatabase(path, knownPaths(live))
							CachedConnection(engine.openServerConnection(path, writable = false), ids)
						}.connection
				block(conn)
			} catch (e: DebugEngineException) {
				val error = engineError(e)
				// The time limit counts the call's waits for the lock, so a quick query can run out of time
				// behind a slow call: say so, for the agent to retry, not simplify it.
				val waited = call.waitedMs
				if (e.sqliteCode == DebugEngineException.SQLITE_INTERRUPT && waited * 2 >= DEADLINE_MS) {
					throw McpToolError(
						"${error.message} (this call waited ${waited / 1000.0} s of its ${DEADLINE_MS / 1000} s for another tool " +
							"call to finish; tool calls run one at a time, so retry it)",
						error.details,
					)
				}
				throw error
			}
		}

	/**
	 * Closes cached server connections whose file may be gone: after the app deletes and recreates a
	 * database, a connection opened before still reads the old file. A connection is kept while some
	 * app connection to its path that was there when it opened is still open; a path no app connection
	 * has any more, or only new ones, is stale. Call with [lock] held.
	 */
	private fun dropStaleServerConnections(live: List<AppConnection>) {
		val it = serverConnections.entries.iterator()
		while (it.hasNext()) {
			val (path, cached) = it.next()
			val ids = live.filter { c -> c.path == path && !c.closing }.map { c -> c.id }
			if (ids.none { id -> id in cached.appConnectionIds }) {
				runCatching { cached.connection.close() }
				it.remove()
			}
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

	/** Values to bind: by position when [names] is null, else `names[i]` names the parameter of `values[i]`. */
	private class Bind(
		val values: List<Any?>,
		val names: List<String>?,
	) {
		companion object {
			val NONE = Bind(emptyList(), null)
		}
	}

	private fun bindArgs(args: JSONObject): Bind {
		fun value(
			v: Any?,
			what: String,
		): Any? =
			when (v) {
				null, JSONObject.NULL -> null
				is Int, is Long, is Short -> (v as Number).toLong()
				is Number -> v.toDouble()
				is String -> v
				is Boolean -> if (v) 1L else 0L
				else -> throw McpToolError("$what must be null, a number or a string.")
			}
		return when (val raw = args.opt("args")) {
			null, JSONObject.NULL -> {
				Bind.NONE
			}

			is JSONArray -> {
				Bind((0 until raw.length()).map { i -> value(raw.opt(i), "args[$i]") }, null)
			}

			is JSONObject -> {
				val names =
					raw
						.keys()
						.asSequence()
						.sorted()
						.toList()
				names.forEach { if (it.isEmpty()) throw McpToolError("args has an empty name.") }
				Bind(names.map { value(raw.opt(it), "args.$it") }, names)
			}

			else -> {
				throw McpToolError("args must be an array or an object.")
			}
		}
	}
}

/** Runs the idle check later; a test seam, the real one is [TimerIdleScheduler]. */
internal fun interface IdleScheduler {
	/** Runs [task] once after [delayMs]; the result cancels it. */
	fun schedule(
		delayMs: Long,
		task: Runnable,
	): () -> Unit
}

/** One daemon timer thread per pending check, ended by its cancel. */
private class TimerIdleScheduler : IdleScheduler {
	override fun schedule(
		delayMs: Long,
		task: Runnable,
	): () -> Unit {
		val timer = Timer("sqlite-debugger-idle", true)
		timer.schedule(
			object : TimerTask() {
				override fun run() = task.run()
			},
			delayMs,
		)
		return { timer.cancel() }
	}
}
