package org.appdevforall.sqlitedebugger.mcp

import org.appdevforall.sqlitedebugger.engine.AppConnection
import org.appdevforall.sqlitedebugger.engine.DebugEngine
import org.appdevforall.sqlitedebugger.engine.DebugEngineException
import org.appdevforall.sqlitedebugger.engine.EventKind
import org.appdevforall.sqlitedebugger.engine.ExecuteResult
import org.appdevforall.sqlitedebugger.engine.FakeDebugEngine
import org.appdevforall.sqlitedebugger.engine.QueryLimits
import org.appdevforall.sqlitedebugger.engine.QueryResult
import org.appdevforall.sqlitedebugger.engine.ServerConnection
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class DatabaseToolsTest {
	private val engine = FakeDebugEngine()
	private var now = 1_000_000L
	private var dir: File? = null
	private val tools get() = DatabaseTools(engine, dir, { now })
	private val seenSql = mutableListOf<String>()

	private fun conn(
		id: Long,
		path: String,
		closing: Boolean = false,
	) = AppConnection(id, path, false, closing, 1_700_000_000_000_000_000L + id, 5)

	private fun runTool(
		t: DatabaseTools,
		name: String,
		args: JSONObject = JSONObject(),
	): JSONObject {
		val protocol = McpProtocol(t.tools(), "test", { "s" }, 4)
		val init =
			JSONObject()
				.put("jsonrpc", "2.0")
				.put("id", 1)
				.put("method", "initialize")
				.put("params", JSONObject().put("protocolVersion", "2025-06-18"))
				.toString()
		val session = protocol.post(init, null, null).headers[McpProtocol.SESSION_HEADER]!!
		val call =
			JSONObject()
				.put("jsonrpc", "2.0")
				.put("id", 2)
				.put("method", "tools/call")
				.put("params", JSONObject().put("name", name).put("arguments", args))
				.toString()
		return JSONObject(protocol.post(call, session, null).body!!).getJSONObject("result")
	}

	private fun ok(
		name: String,
		args: JSONObject = JSONObject(),
		t: DatabaseTools = tools,
	): JSONObject {
		val r = runTool(t, name, args)
		assertFalse(r.toString(), r.getBoolean("isError"))
		return r.getJSONObject("structuredContent")
	}

	private fun db(path: String = "/data/a.db") = JSONObject().put("database", path)

	private fun answerWith(vararg byPrefix: Pair<String, QueryResult>) {
		engine.answer = { _, _, sql, _ ->
			seenSql += sql
			byPrefix.firstOrNull { sql.startsWith(it.first) }?.second ?: QueryResult(emptyList(), emptyList(), 0, false)
		}
	}

	@Test fun everyToolButExecuteIsReadOnly() {
		val list = tools.tools()
		assertEquals(
			listOf(
				"list_databases",
				"list_connections",
				"get_schema",
				"query",
				"execute",
				"explain_query_plan",
				"get_stats",
				"integrity_check",
				"get_events",
			),
			list.map { it.name },
		)
		for (tool in list) {
			val annotations = tool.describe().getJSONObject("annotations")
			assertEquals(tool.name, tool.name != "execute", annotations.getBoolean("readOnlyHint"))
			assertEquals(tool.name, tool.name == "execute", annotations.getBoolean("destructiveHint"))
		}
	}

	@Test fun everyToolThatRunsAStatementSaysAStatementOnANonWalDatabaseStopsAfterOneAndAHalfSeconds() {
		val list = tools.tools().associateBy { it.name }
		listOf("get_schema", "query", "explain_query_plan", "get_stats", "integrity_check").forEach { name ->
			val d = list.getValue(name).describe().getString("description")
			assertTrue("$name: $d", d.contains("not in WAL mode a statement is stopped after 1.5 s"))
			assertTrue("$name: $d", d.contains("WAL mode has no such limit"))
		}
		assertTrue(
			list
				.getValue("integrity_check")
				.describe()
				.getString("description")
				.contains("will stop early"),
		)
		// the text has to say what the C constant is (the unit tests run in the module's folder)
		assertTrue(File("src/main/cpp/sqlitedbg.h").readText().contains("#define SQLITEDBG_SERVER_NON_WAL_MS 1500\n"))
	}

	@Test fun listDatabasesGroupsByPathAndSkipsEmpty() {
		engine.appConnections += listOf(conn(1, "/data/a.db"), conn(2, "/data/a.db"), conn(3, ""), conn(4, "/data/b.db"))
		val dbs = ok("list_databases").getJSONArray("databases")
		assertEquals(2, dbs.length())
		assertEquals("/data/a.db", dbs.getJSONObject(0).getString("path"))
		assertEquals(2, dbs.getJSONObject(0).getInt("connections"))
		assertTrue(dbs.getJSONObject(1).getBoolean("available"))
	}

	@Test fun listDatabasesCallsAPathAvailableOnlyWhileAnAppConnectionIsNotClosing() {
		engine.appConnections +=
			listOf(
				conn(1, "/data/a.db", closing = true),
				conn(2, "/data/a.db", closing = true),
				conn(3, "/data/b.db", closing = true),
				conn(4, "/data/b.db"),
			)
		val dbs = ok("list_databases").getJSONArray("databases")
		assertEquals(2, dbs.length())
		assertEquals("/data/a.db", dbs.getJSONObject(0).getString("path"))
		assertFalse("every connection to a.db is closing", dbs.getJSONObject(0).getBoolean("available"))
		assertEquals(2, dbs.getJSONObject(0).getInt("connections"))
		assertTrue("b.db has one connection that is not closing", dbs.getJSONObject(1).getBoolean("available"))
	}

	@Test fun theUnknownDatabaseErrorListsNoPathWhoseConnectionsAreAllClosing() {
		engine.appConnections += listOf(conn(1, "/data/a.db", closing = true), conn(2, "/data/b.db"))
		val r = runTool(tools, "query", db("/data/a.db").put("sql", "select 1"))
		assertTrue(r.toString(), r.getBoolean("isError"))
		assertEquals(
			listOf("/data/b.db"),
			r.getJSONObject("structuredContent").getJSONArray("available").let { a ->
				(0 until a.length()).map { a.getString(it) }
			},
		)
		assertTrue(engine.openedServerConnections.isEmpty())
		assertEquals(0, engine.openAttempts)
	}

	@Test fun listDatabasesAddsUnopenedFilesButNotSideFiles() {
		val d =
			kotlin.io.path
				.createTempDirectory("dbs")
				.toFile()
		try {
			listOf("open.db", "closed.db", "closed.db-wal", "closed.db-shm", "closed.db-journal").forEach { File(d, it).writeText("x") }
			dir = d
			engine.appConnections += conn(1, File(d, "open.db").absolutePath)
			val dbs = ok("list_databases").getJSONArray("databases")
			assertEquals(2, dbs.length())
			assertTrue(dbs.getJSONObject(0).getBoolean("available"))
			val closed = dbs.getJSONObject(1)
			assertEquals(File(d, "closed.db").absolutePath, closed.getString("path"))
			assertFalse(closed.getBoolean("available"))
		} finally {
			d.deleteRecursively()
		}
	}

	@Test fun listDatabasesMatchesOpenFilesThroughASymlinkedFolder() {
		val root =
			kotlin.io.path
				.createTempDirectory("dbs")
		try {
			val real =
				java.nio.file.Files
					.createDirectory(root.resolve("real"))
					.toFile()
			val link = root.resolve("link")
			java.nio.file.Files
				.createSymbolicLink(link, real.toPath())
			listOf("open.db", "closed.db").forEach { File(real, it).writeText("x") }
			// the folder is listed through the link, the connection reports the real path
			dir = link.toFile()
			engine.appConnections += conn(1, File(real, "open.db").absolutePath)
			var dbs = ok("list_databases").getJSONArray("databases")
			assertEquals(dbs.toString(), 2, dbs.length())
			assertEquals(File(real, "open.db").absolutePath, dbs.getJSONObject(0).getString("path"))
			assertTrue(dbs.getJSONObject(0).getBoolean("available"))
			assertFalse(dbs.getJSONObject(1).getBoolean("available"))
			assertTrue(dbs.getJSONObject(1).getString("path").endsWith("closed.db"))

			// and the other way round: the connection reports the link, the folder is the real one
			engine.appConnections.clear()
			dir = real
			engine.appConnections += conn(2, File(link.toFile(), "open.db").absolutePath)
			dbs = ok("list_databases").getJSONArray("databases")
			assertEquals(dbs.toString(), 2, dbs.length())
			assertEquals(File(link.toFile(), "open.db").absolutePath, dbs.getJSONObject(0).getString("path"))
		} finally {
			root.toFile().deleteRecursively()
		}
	}

	@Test fun listConnectionsEncodesBigIntegersAsStrings() {
		engine.appConnections += conn(1, "/data/a.db", closing = true)
		val c = ok("list_connections").getJSONArray("connections").getJSONObject(0)
		assertEquals(1, c.getInt("id"))
		assertTrue(c.getBoolean("closing"))
		assertEquals("1700000000000000001", c.getString("opened_at_epoch_nanos"))
	}

	@Test fun everyDatabaseToolRefusesAnUnknownPathBeforeTheEngine() {
		engine.appConnections += conn(1, "/data/a.db")
		val cases =
			listOf(
				"get_schema" to JSONObject(),
				"query" to JSONObject().put("sql", "select 1"),
				"explain_query_plan" to JSONObject().put("sql", "select 1"),
				"get_stats" to JSONObject(),
				"integrity_check" to JSONObject(),
				"execute" to JSONObject().put("sql", "delete from t"),
			)
		for ((name, extra) in cases) {
			for (bad in listOf("/data/other.db", "", "/data/a.db-wal")) {
				val r = runTool(tools, name, db(bad).also { o -> extra.keys().forEach { k -> o.put(k, extra.get(k)) } })
				assertTrue("$name $bad", r.getBoolean("isError"))
			}
			val missing = runTool(tools, name, extra)
			assertTrue(name, missing.getBoolean("isError"))
		}
		assertTrue(engine.openedServerConnections.isEmpty())
		assertEquals(0, engine.openAttempts)
	}

	@Test fun aQueryRefusesAPathWhoseLastAppConnectionClosedAfterTheFirstCheck() {
		engine.appConnections += conn(1, "/data/a.db")
		// the app closes its last connection to a.db right after checkedPath's snapshot
		val racing =
			object : DebugEngine by engine {
				private var snapshots = 0

				override fun connections() =
					engine.connections().also {
						if (++snapshots ==
							1
						) {
							engine.appConnections.removeAll { c -> c.path == "/data/a.db" }
						}
					}
			}
		engine.appConnections += conn(2, "/data/b.db")
		val r = runTool(DatabaseTools(racing, dir, { now }), "query", db().put("sql", "select 1"))
		assertTrue(r.getBoolean("isError"))
		assertTrue(
			r.toString(),
			r
				.getJSONArray("content")
				.getJSONObject(0)
				.getString("text")
				.startsWith("Unknown database"),
		)
		assertTrue(engine.openedServerConnections.isEmpty())
		assertEquals(0, engine.openAttempts)
	}

	@Test fun aQueryRefusesAPathWhoseLastAppConnectionBeganClosingAfterTheFirstCheck() {
		engine.appConnections += conn(1, "/data/a.db")
		engine.appConnections += conn(2, "/data/b.db")
		// the app begins closing its last connection to a.db right after checkedPath's snapshot; it stays listed
		val racing =
			object : DebugEngine by engine {
				private var snapshots = 0

				override fun connections() =
					engine.connections().also {
						if (++snapshots == 1) {
							engine.appConnections.replaceAll { c -> if (c.path == "/data/a.db") c.copy(closing = true) else c }
						}
					}
			}
		val r = runTool(DatabaseTools(racing, dir, { now }), "query", db().put("sql", "select 1"))
		assertTrue(r.toString(), r.getBoolean("isError"))
		assertTrue(
			r.toString(),
			r
				.getJSONArray("content")
				.getJSONObject(0)
				.getString("text")
				.startsWith("Unknown database"),
		)
		assertEquals(emptyList<Pair<String, Boolean>>(), engine.openedServerConnections)
		assertEquals(0, engine.openAttempts)
	}

	@Test fun emptyPathOfAnInMemoryConnectionIsNotOpenable() {
		engine.appConnections += conn(1, "")
		assertTrue(runTool(tools, "get_stats", db("")).getBoolean("isError"))
		assertTrue(engine.openedServerConnections.isEmpty())
		assertEquals(0, engine.openAttempts)
	}

	@Test fun queryOpensReadOnlyAndPages() {
		engine.appConnections += conn(1, "/data/a.db")
		engine.answer = { _, _, _, _ -> QueryResult(listOf("n"), (1L..5L).map { listOf(it) }, 0, false) }
		val r = ok("query", db().put("sql", "select n from t").put("max_rows", 2).put("offset", 1))
		assertEquals(listOf(listOf("/data/a.db", false)), engine.openedServerConnections.map { listOf(it.first, it.second) })
		assertEquals(2, r.getInt("row_count"))
		assertEquals(2, r.getJSONArray("rows").getJSONArray(0).getInt(0))
		assertTrue(r.getBoolean("has_more"))
		assertEquals(3, r.getInt("next_offset"))
		assertTrue(r.getString("note").contains("re-runs"))
		assertTrue(
			tools
				.tools()
				.first { it.name == "query" }
				.description
				.contains("re-runs"),
		)
	}

	@Test fun toolDescriptionsStateTheLimitsAnAgentMustPlanAround() {
		val descriptions = tools.tools().associate { it.name to it.description }
		// The server's own connection lacks what the app registers per connection (sqlite-js, requery custom functions).
		for (name in listOf("query", "explain_query_plan")) {
			assertTrue(name, descriptions.getValue(name).contains("Functions the app adds to its own connections"))
			assertTrue(name, descriptions.getValue(name).contains("no such function"))
		}
		assertTrue(descriptions.getValue("query").contains("compute in plain SQL"))
		// get_schema has no offset, and either limit can leave entries out.
		val schema = descriptions.getValue("get_schema")
		assertTrue(schema, schema.contains("past the first ${DatabaseTools.MAX_ROWS}"))
		assertTrue(schema, schema.contains("past about 4 MiB of cell bytes"))
		assertTrue(schema, schema.contains("page through sqlite_master with query (ORDER BY type, name, the order get_schema uses)"))
		assertTrue(schema, schema.contains("counts characters, not bytes"))
		// A full integrity_check evaluates the app's index functions; quick_check does not.
		val integrity = descriptions.getValue("integrity_check")
		assertTrue(integrity, integrity.contains("Both checks fail with \"unknown function\" when a CHECK constraint"))
		assertTrue(integrity, integrity.contains("NOT NULL virtual generated column"))
		assertTrue(integrity, integrity.contains("full integrity_check also fails on an index"))
		assertTrue(integrity, integrity.contains("partial index"))
		assertTrue(integrity, integrity.contains("quick_check does not fail on those two"))
	}

	@Test fun queryChecksItsArguments() {
		engine.appConnections += conn(1, "/data/a.db")

		fun bad(vararg kv: Pair<String, Any>) =
			assertTrue(
				kv.toString(),
				runTool(tools, "query", db().also { o -> kv.forEach { o.put(it.first, it.second) } }).getBoolean("isError"),
			)
		bad()
		bad("sql" to "x", "max_rows" to 1001)
		bad("sql" to "x", "max_rows" to 0)
		bad("sql" to "x", "offset" to -1)
		bad("sql" to "x", "max_rows" to 1.5)
		bad("sql" to "x", "args" to "nope")
		bad("sql" to "x", "args" to JSONArray().put(JSONObject()))
		assertTrue(engine.openedServerConnections.isEmpty())
		assertEquals(0, engine.openAttempts)
	}

	@Test fun queryBindsArgsByType() {
		engine.appConnections += conn(1, "/data/a.db")
		var got: List<Any?> = emptyList()
		engine.answer = { _, _, _, a ->
			got = a
			QueryResult(emptyList(), emptyList(), 0, false)
		}
		ok(
			"query",
			db().put("sql", "x").put(
				"args",
				JSONArray()
					.put(JSONObject.NULL)
					.put(3)
					.put(2.5)
					.put("s")
					.put(true),
			),
		)
		assertEquals(listOf(null, 3L, 2.5, "s", 1L), got)
	}

	@Test fun queryErrorsCarryTheSqliteCode() {
		engine.appConnections += conn(1, "/data/a.db")
		engine.answer = { _, _, _, _ -> throw DebugEngineException(DebugEngineException.SQLITE_AUTH, "not authorized") }
		val r = runTool(tools, "query", db().put("sql", "delete from t"))
		assertTrue(r.getBoolean("isError"))
		assertEquals(23, r.getJSONObject("structuredContent").getInt("sqlite_code"))
	}

	@Test fun deadlineIsFiftySecondsAhead() {
		engine.appConnections += conn(1, "/data/a.db")
		var deadline = 0L
		val real = engine
		engine.answer = { _, _, _, _ -> QueryResult(emptyList(), emptyList(), 0, false) }
		// the fake ignores the deadline, so check the arithmetic through the constant and a wrapper engine
		val wrapper =
			object : org.appdevforall.sqlitedebugger.engine.DebugEngine by real {
				override fun openServerConnection(
					path: String,
					writable: Boolean,
				) = real.openServerConnection(path, writable).let { c ->
					object : org.appdevforall.sqlitedebugger.engine.ServerConnection by c {
						override fun query(
							sql: String,
							args: List<Any?>,
							offset: Long,
							limits: org.appdevforall.sqlitedebugger.engine.QueryLimits,
							deadlineMillis: Long,
						): QueryResult {
							deadline = deadlineMillis
							return c.query(sql, args, offset, limits, deadlineMillis)
						}
					}
				}
			}
		ok("query", db().put("sql", "x"), DatabaseTools(wrapper, null, { now }))
		assertEquals(now + 50_000, deadline)
	}

	/** [engine], refusing like the native one to start a query whose deadline has passed. */
	private fun nativeDeadline(clock: () -> Long) =
		object : DebugEngine by engine {
			override fun openServerConnection(
				path: String,
				writable: Boolean,
			) = engine.openServerConnection(path, writable).let { c ->
				object : ServerConnection by c {
					override fun query(
						sql: String,
						args: List<Any?>,
						offset: Long,
						limits: QueryLimits,
						deadlineMillis: Long,
					): QueryResult {
						if (clock() >= deadlineMillis) {
							throw DebugEngineException(
								DebugEngineException.SQLITE_INTERRUPT,
								"the time limit passed before the query started",
							)
						}
						return c.query(sql, args, offset, limits, deadlineMillis)
					}
				}
			}
		}

	/**
	 * Runs tool [name] while another call holds the lock: it waits for it [waitMs] of the fake clock
	 * (in the idle check, its first wait), then its query is answered by [then]. Returns its result.
	 */
	private fun queuedBehindAnotherCall(
		waitMs: Long,
		then: () -> QueryResult,
		name: String = "query",
		args: JSONObject = db().put("sql", "select 1"),
	): JSONObject {
		engine.appConnections += conn(1, "/data/a.db")
		val time = AtomicLong(now)
		val holding = CountDownLatch(1)
		val release = CountDownLatch(1)
		engine.answer = { _, _, sql, _ ->
			if (sql == "slow") {
				holding.countDown()
				release.await()
				QueryResult(emptyList(), emptyList(), 0, false)
			} else {
				then()
			}
		}
		val t = DatabaseTools(nativeDeadline(time::get), null, time::get)
		val slow = Thread({ ok("query", db().put("sql", "slow"), t) }, "dbtools-slow")
		var result: JSONObject? = null
		val queued = Thread({ result = runTool(t, name, args) }, "dbtools-queued")
		try {
			slow.start()
			assertTrue("the slow call never reached the engine", holding.await(10, TimeUnit.SECONDS))
			queued.start()
			// BLOCKED is a wait to enter a monitor; the slow call holds the tools' lock, and has already loaded every class on the path
			val giveUp = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
			while (queued.state != Thread.State.BLOCKED) {
				assertTrue("the queued call never blocked on the lock: ${queued.state}", System.nanoTime() < giveUp)
				Thread.sleep(5)
			}
			time.addAndGet(waitMs)
		} finally {
			release.countDown()
			queued.takeIf { it.state != Thread.State.NEW }?.join(10_000)
			slow.takeIf { it.state != Thread.State.NEW }?.join(10_000)
		}
		assertFalse("the queued call did not finish", queued.isAlive)
		assertFalse("the slow call did not finish", slow.isAlive)
		return result!!
	}

	/**
	 * [engine] with queries that each take [runMs] of the fake clock, as the native one stops a query at its deadline
	 * and refuses to start one past it. [started] lists the queries that began.
	 */
	private fun slowQueries(
		runMs: Long,
		started: MutableList<String>,
	) = object : DebugEngine by engine {
		override fun openServerConnection(
			path: String,
			writable: Boolean,
		) = engine.openServerConnection(path, writable).let { c ->
			object : ServerConnection by c {
				override fun query(
					sql: String,
					args: List<Any?>,
					offset: Long,
					limits: QueryLimits,
					deadlineMillis: Long,
				): QueryResult {
					if (now >= deadlineMillis) {
						throw DebugEngineException(DebugEngineException.SQLITE_INTERRUPT, "the time limit passed before the query started")
					}
					started += sql
					if (now + runMs >= deadlineMillis) {
						now = deadlineMillis
						throw DebugEngineException(DebugEngineException.SQLITE_INTERRUPT, "the query ran past its time limit")
					}
					now += runMs
					return c.query(sql, args, offset, limits, deadlineMillis)
				}
			}
		}
	}

	private fun post(
		t: DatabaseTools,
		vararg sql: String,
	): JSONArray {
		val protocol = McpProtocol(t.tools(), "test", { "s" }, 4, DatabaseTools.DEADLINE_MS) { now }
		val session = protocol.post(initBody(), null, null).headers[McpProtocol.SESSION_HEADER]!!
		val batch = JSONArray()
		sql.forEachIndexed { i, text ->
			val args = db().put("sql", text)
			val params = JSONObject().put("name", "query").put("arguments", args)
			batch.put(
				JSONObject()
					.put("jsonrpc", "2.0")
					.put("id", i)
					.put("method", "tools/call")
					.put("params", params),
			)
		}
		return JSONArray(protocol.post(batch.toString(), session, null).body!!)
	}

	private fun initBody() =
		JSONObject()
			.put("jsonrpc", "2.0")
			.put("id", 1)
			.put("method", "initialize")
			.put("params", JSONObject().put("protocolVersion", "2025-06-18"))
			.toString()

	private fun resultText(reply: JSONObject) =
		reply
			.getJSONObject("result")
			.getJSONArray("content")
			.getJSONObject(0)
			.getString("text")

	@Test fun aBatchOfSlowCallsEndsWithinOneDeadlineAndTheLateCallsFailAtOnce() {
		engine.appConnections += conn(1, "/data/a.db")
		val started = mutableListOf<String>()
		val t = DatabaseTools(slowQueries(20_000, started), dir, { now })
		val start = now
		val replies = post(t, "q1", "q2", "q3", "q4", "q5")
		assertEquals("one deadline for the POST, not one per call", DatabaseTools.DEADLINE_MS, now - start)
		assertEquals(listOf("q1", "q2", "q3"), started) // q3 ran into the deadline; q4 and q5 never started
		assertFalse(replies.getJSONObject(1).getJSONObject("result").getBoolean("isError"))
		assertEquals("the query ran past its time limit", resultText(replies.getJSONObject(2)))
		for (i in 3..4) {
			assertTrue(replies.getJSONObject(i).getJSONObject("result").getBoolean("isError"))
			assertEquals("the time limit passed before the query started", resultText(replies.getJSONObject(i)))
		}
	}

	@Test fun aSingleCallKeepsItsOwnFullDeadlineAndEachPostStartsANewOne() {
		engine.appConnections += conn(1, "/data/a.db")
		val started = mutableListOf<String>()
		val t = DatabaseTools(slowQueries(20_000, started), dir, { now })
		val start = now
		assertFalse(post(t, "q1").getJSONObject(0).getJSONObject("result").getBoolean("isError"))
		assertEquals(20_000, now - start)
		assertFalse("a new POST has a new deadline", post(t, "q2").getJSONObject(0).getJSONObject("result").getBoolean("isError"))
		assertEquals(40_000, now - start)
		val slow = DatabaseTools(slowQueries(DatabaseTools.DEADLINE_MS + 1, started), dir, { now })
		val before = now
		val r = post(slow, "q3").getJSONObject(0)
		assertEquals("the query ran past its time limit", resultText(r))
		assertEquals(DatabaseTools.DEADLINE_MS, now - before)
	}

	private val answersEmpty: () -> QueryResult = { QueryResult(emptyList(), emptyList(), 0, false) }

	private val ranPastItsLimit: () -> QueryResult = {
		throw DebugEngineException(DebugEngineException.SQLITE_INTERRUPT, "the query ran past its time limit")
	}

	private fun errorText(r: JSONObject): String {
		assertTrue(r.toString(), r.getBoolean("isError"))
		return r.getJSONArray("content").getJSONObject(0).getString("text")
	}

	@Test(timeout = 10_000)
	fun aCallWhoseTimeRanOutWaitingForAnotherCallSaysSo() {
		val r = queuedBehindAnotherCall(DatabaseTools.DEADLINE_MS + 1, answersEmpty)
		assertEquals(
			"the time limit passed before the query started (this call waited 50.001 s of its 50 s for another tool call to finish; " +
				"tool calls run one at a time, so retry it)",
			errorText(r),
		)
		assertEquals(DebugEngineException.SQLITE_INTERRUPT, r.getJSONObject("structuredContent").getInt("sqlite_code"))
	}

	@Test(timeout = 10_000)
	fun aCallThatWaitedHalfItsTimeLimitSaysSo() {
		val r = queuedBehindAnotherCall(DatabaseTools.DEADLINE_MS / 2, ranPastItsLimit)
		assertTrue(errorText(r), errorText(r).startsWith("the query ran past its time limit (this call waited 25.0 s of its 50 s"))
	}

	@Test(timeout = 10_000)
	fun getStatsCountsTheWaitForTheLockInItsTimeLimit() {
		val r = queuedBehindAnotherCall(DatabaseTools.DEADLINE_MS, answersEmpty, "get_stats", db())
		val text = errorText(r)
		assertTrue(text, text.startsWith("the time limit passed before the query started (this call waited 50.0 s of its 50 s"))
	}

	@Test(timeout = 10_000)
	fun aQueryThatUsedMostOfItsOwnTimeIsNotTiedToTheWait() {
		val r = queuedBehindAnotherCall(10_000, ranPastItsLimit)
		assertEquals("the query ran past its time limit", errorText(r))
	}

	@Test fun jsonEncodesNonFiniteDoublesBigIntegersAndBlobs() {
		engine.appConnections += conn(1, "/data/a.db")
		val big = 9_007_199_254_740_993L
		engine.answer = { _, _, _, _ ->
			QueryResult(
				listOf("a", "b", "c", "d", "e", "f", "g", "h"),
				listOf(
					listOf(
						Double.NaN,
						Double.POSITIVE_INFINITY,
						Double.NEGATIVE_INFINITY,
						big,
						-big,
						9_007_199_254_740_992L,
						byteArrayOf(1, 2, 3),
						null,
					),
				),
				0,
				false,
			)
		}
		val row = ok("query", db().put("sql", "x")).getJSONArray("rows").getJSONArray(0)
		assertEquals("NaN", row.get(0))
		assertEquals("Infinity", row.get(1))
		assertEquals("-Infinity", row.get(2))
		assertEquals("9007199254740993", row.get(3))
		assertEquals("-9007199254740993", row.get(4))
		assertEquals(9_007_199_254_740_992L, row.getLong(5))
		val blob = row.getJSONObject(6)
		assertEquals("AQID", blob.getString("base64"))
		assertEquals(3, blob.getInt("bytes"))
		assertFalse(blob.getBoolean("truncated"))
		assertTrue(row.isNull(7))
		assertEquals(true, (DatabaseTools.encodeValue(ByteArray(8), truncated = true) as JSONObject).getBoolean("truncated"))
		assertEquals(false, (DatabaseTools.encodeValue(ByteArray(1024)) as JSONObject).getBoolean("truncated"))
		assertEquals(1.5, DatabaseTools.encodeValue(1.5))
	}

	@Test fun aBlobExactlyAtTheLimitIsNotTruncatedButOneByteMoreIs() {
		engine.appConnections += conn(1, "/data/a.db")
		val whole = ByteArray(DatabaseTools.MAX_BLOB_BYTES)
		val cut = ByteArray(DatabaseTools.MAX_BLOB_BYTES) // the engine kept maxBlobBytes of a longer value
		engine.answer = { _, _, _, _ ->
			QueryResult(listOf("a", "b"), listOf(listOf(whole, cut)), 1, false, setOf(1L))
		}
		val r = ok("query", db().put("sql", "x"))
		val row = r.getJSONArray("rows").getJSONArray(0)
		assertFalse(row.getJSONObject(0).getBoolean("truncated"))
		assertTrue(row.getJSONObject(1).getBoolean("truncated"))
		assertEquals(1, r.getInt("truncated_cells"))
	}

	private fun cutCells(r: JSONObject): List<Pair<Int, Int>> =
		r.getJSONArray("truncated_cell_indexes").let { a ->
			(0 until a.length()).map { a.getJSONArray(it).let { p -> p.getInt(0) to p.getInt(1) } }
		}

	@Test fun queryListsExactlyTheCutCellsTextAndBlobAlike() {
		engine.appConnections += conn(1, "/data/a.db")
		val cutText = "x".repeat(DatabaseTools.MAX_TEXT_BYTES)
		val cutBlob = ByteArray(DatabaseTools.MAX_BLOB_BYTES)
		engine.answer = { _, _, _, _ ->
			QueryResult(
				listOf("a", "b", "c"),
				listOf(listOf(cutText, "short", cutText), listOf("short", cutBlob, 5L), listOf("short", "short", "short")),
				3,
				false,
				setOf(0L, 2L, 4L),
			)
		}
		val r = ok("query", db().put("sql", "x"))
		assertEquals(listOf(0 to 0, 0 to 2, 1 to 1), cutCells(r))
		assertEquals(3, r.getInt("truncated_cells"))
		val rows = r.getJSONArray("rows")
		assertEquals("a TEXT cell stays a string", cutText, rows.getJSONArray(0).get(0))
		assertTrue(rows.getJSONArray(1).getJSONObject(1).getBoolean("truncated"))
	}

	@Test fun queryListsNoCutCellsWhenNothingWasCut() {
		engine.appConnections += conn(1, "/data/a.db")
		engine.answer = { _, _, _, _ -> QueryResult(listOf("a"), listOf(listOf("x")), 0, false) }
		val r = ok("query", db().put("sql", "x"))
		assertEquals(0, r.getJSONArray("truncated_cell_indexes").length())
		assertEquals(0, r.getInt("truncated_cells"))
	}

	@Test fun explainAndIntegrityCheckListCutCellsAndGetSchemaDoesNot() {
		engine.appConnections += conn(1, "/data/a.db")
		answerWith(
			"EXPLAIN" to
				QueryResult(listOf("id", "parent", "notused", "detail"), listOf(listOf(2L, 0L, 0L, "SCAN t")), 1, false, setOf(3L)),
			"PRAGMA quick_check" to QueryResult(listOf("quick_check"), listOf(listOf("ok"), listOf("bad")), 1, false, setOf(1L)),
			"SELECT type" to
				QueryResult(listOf("type", "name", "tbl_name", "sql"), listOf(listOf("table", "t", "t", "CREATE")), 1, false, setOf(3L)),
		)
		val plan = ok("explain_query_plan", db().put("sql", "select 1"))
		assertEquals(listOf(0 to 3), cutCells(plan))
		assertEquals(1, plan.getInt("truncated_cells"))
		val check = ok("integrity_check", db())
		assertEquals(listOf(1 to 0), cutCells(check))
		assertEquals(1, check.getInt("truncated_cells"))
		val schema = ok("get_schema", db())
		assertFalse("each entry says whether it was cut", schema.has("truncated_cell_indexes"))
		assertTrue(schema.getJSONArray("tables").getJSONObject(0).getBoolean("truncated"))
	}

	@Test fun theToolDescriptionsExplainTheCutCellList() {
		val byName = tools.tools().associateBy { it.name }
		for (name in listOf("query", "explain_query_plan", "integrity_check", "get_schema")) {
			assertTrue(name, byName.getValue(name).description.contains("truncated_cell_indexes"))
		}
		assertTrue(byName.getValue("query").description.contains("a cut BLOB is in the list too"))
	}

	@Test fun aResultStopsAtTheTotalBudgetAndPages() {
		engine.appConnections += conn(1, "/data/a.db")
		val big = "x".repeat(3 * 1024 * 1024)
		engine.answer = { _, _, _, _ -> QueryResult(listOf("v"), listOf(listOf(big), listOf(big), listOf(big)), 0, false) }
		val r = ok("query", db().put("sql", "x"))
		assertEquals(1, r.getInt("row_count"))
		assertTrue(r.getBoolean("has_more"))
		assertEquals(1, r.getInt("next_offset"))
		val next = ok("query", db().put("sql", "x").put("offset", 1))
		assertEquals(1, next.getInt("row_count"))
		assertEquals(2, next.getInt("next_offset"))
		assertEquals(4 * 1024 * 1024, DatabaseTools.MAX_TOTAL_BYTES)
	}

	@Test fun getSchemaGroupsByType() {
		engine.appConnections += conn(1, "/data/a.db")
		answerWith(
			"SELECT type" to
				QueryResult(
					listOf("type", "name", "tbl_name", "sql"),
					listOf(
						listOf("index", "i1", "t", "CREATE INDEX i1 ON t(a)"),
						listOf("table", "t", "t", "CREATE TABLE t(a)"),
						listOf("trigger", "tr", "t", "CREATE TRIGGER tr ..."),
						listOf("view", "v", "v", "CREATE VIEW v AS ..."),
					),
					0,
					false,
				),
		)
		val r = ok("get_schema", db())
		assertEquals("t", r.getJSONArray("tables").getJSONObject(0).getString("name"))
		assertEquals("CREATE INDEX i1 ON t(a)", r.getJSONArray("indexes").getJSONObject(0).getString("sql"))
		assertEquals(1, r.getJSONArray("views").length())
		assertEquals(1, r.getJSONArray("triggers").length())
		assertTrue(seenSql.single().contains("FROM sqlite_master"))
		assertFalse(seenSql.single().contains("sqlite_schema")) // an alias that needs SQLite 3.33
		assertFalse(r.getBoolean("truncated"))
		assertEquals(0, r.getInt("truncated_cells"))
		assertFalse(r.getJSONArray("tables").getJSONObject(0).getBoolean("truncated"))
	}

	@Test fun getSchemaSaysWhichCreateStatementWasCut() {
		engine.appConnections += conn(1, "/data/a.db")
		// the engine kept MAX_SCHEMA_SQL_BYTES of a longer statement
		val cut = "CREATE VIEW big AS SELECT " + "1 + ".repeat((DatabaseTools.MAX_SCHEMA_SQL_BYTES - 26) / 4)
		answerWith(
			"SELECT type" to
				QueryResult(
					listOf("type", "name", "tbl_name", "sql"),
					listOf(listOf("table", "t", "t", "CREATE TABLE t(a)"), listOf("view", "big", "big", cut)),
					1,
					false,
					setOf(7L), // row 1, column 3
				),
		)
		val r = ok("get_schema", db())
		assertTrue("a cut CREATE statement makes the result truncated", r.getBoolean("truncated"))
		assertEquals(1, r.getInt("truncated_cells"))
		assertTrue(r.getJSONArray("views").getJSONObject(0).getBoolean("truncated"))
		assertEquals(cut, r.getJSONArray("views").getJSONObject(0).getString("sql"))
		assertFalse(r.getJSONArray("tables").getJSONObject(0).getBoolean("truncated"))
	}

	@Test fun getSchemaIsTruncatedPastMaxRowsEntries() {
		engine.appConnections += conn(1, "/data/a.db")
		val rows = (0..DatabaseTools.MAX_ROWS).map { listOf("table", "t$it", "t$it", "CREATE TABLE t$it(a)") }
		answerWith("SELECT type" to QueryResult(listOf("type", "name", "tbl_name", "sql"), rows, 0, false))
		val r = ok("get_schema", db())
		assertEquals(DatabaseTools.MAX_ROWS, r.getJSONArray("tables").length())
		assertTrue(r.getBoolean("truncated"))
		assertEquals(0, r.getInt("truncated_cells"))
	}

	@Test fun getSchemaIsTruncatedWhenTheResultBudgetIsSpent() {
		engine.appConnections += conn(1, "/data/a.db")
		// statements at the per-cell limit: the 4 MiB budget ends the result long before MAX_ROWS entries
		val big = "CREATE VIEW v AS SELECT '" + "x".repeat(DatabaseTools.MAX_SCHEMA_SQL_BYTES - 27) + "'"
		val rows = (0 until 70).map { listOf("view", "v$it", "v$it", big) }
		answerWith("SELECT type" to QueryResult(listOf("type", "name", "tbl_name", "sql"), rows, 0, false))
		val r = ok("get_schema", db())
		val views = r.getJSONArray("views").length()
		assertTrue("$views views", views in 1 until DatabaseTools.MAX_TOTAL_BYTES / DatabaseTools.MAX_SCHEMA_SQL_BYTES)
		assertTrue(r.getBoolean("truncated"))
		assertEquals(0, r.getInt("truncated_cells"))
	}

	@Test fun explainQueryPlanPrefixesTheSql() {
		engine.appConnections += conn(1, "/data/a.db")
		answerWith(
			"EXPLAIN QUERY PLAN" to
				QueryResult(
					listOf("id", "parent", "notused", "detail"),
					listOf(listOf(3L, 0L, 0L, "SCAN t")),
					0,
					false,
				),
		)
		val plan = ok("explain_query_plan", db().put("sql", "select * from t")).getJSONArray("plan")
		assertEquals("SCAN t", plan.getJSONObject(0).getString("detail"))
		assertEquals("EXPLAIN QUERY PLAN select * from t", seenSql.single())
	}

	@Test fun getStatsCountsRowsUnderTenTables() {
		engine.appConnections += conn(1, "/data/a.db")

		fun one(v: Long) = QueryResult(listOf("x"), listOf(listOf(v)), 0, false)
		answerWith(
			"PRAGMA page_count" to one(10),
			"PRAGMA page_size" to one(4096),
			"PRAGMA freelist_count" to one(2),
			"SELECT name" to QueryResult(listOf("name"), listOf(listOf("a\"b"), listOf("c")), 0, false),
			"SELECT COUNT" to one(7),
		)
		val r = ok("get_stats", db())
		assertEquals(10, r.getInt("page_count"))
		assertEquals(4096, r.getInt("page_size"))
		assertEquals(2, r.getInt("freelist_count"))
		assertEquals(40960, r.getInt("size_bytes"))
		assertEquals(7, r.getJSONObject("row_counts").getInt("a\"b"))
		assertTrue(seenSql.contains("SELECT COUNT(*) FROM \"a\"\"b\""))
		assertTrue(seenSql.any { it.contains("FROM sqlite_master") })
		assertTrue(seenSql.none { it.contains("sqlite_schema") })
	}

	@Test fun getStatsKeepsUserTablesThatOnlyLookLikeTheReservedPrefix() {
		engine.appConnections += conn(1, "/data/a.db")
		answerWith("SELECT name" to QueryResult(listOf("name"), listOf(listOf("sqlitecache")), 0, false))
		val r = ok("get_stats", db())
		// "_" is a wildcard in LIKE: without the escape, sqlitecache would be left out of the listing
		val listing = seenSql.single { it.contains("FROM sqlite_master") }
		assertTrue(listing, listing.contains("NOT LIKE 'sqlite\\_%' ESCAPE '\\'"))
		assertTrue(r.getJSONObject("row_counts").has("sqlitecache"))
	}

	@Test fun getStatsSkipsCountsFromTenTables() {
		engine.appConnections += conn(1, "/data/a.db")
		answerWith("SELECT name" to QueryResult(listOf("name"), (1..10).map { listOf("t$it") }, 0, false))
		val r = ok("get_stats", db())
		assertFalse(r.has("row_counts"))
		assertTrue(r.has("row_counts_skipped"))
		assertTrue(seenSql.none { it.startsWith("SELECT COUNT") })
	}

	@Test fun getStatsSaysAtLeastWhenTheTableListHitTheCap() {
		engine.appConnections += conn(1, "/data/a.db")
		val names = (1..DatabaseTools.MAX_ROWS + 5).map { listOf("t$it") }
		answerWith("SELECT name" to QueryResult(listOf("name"), names, 0, false))
		val capped = ok("get_stats", db()).getString("row_counts_skipped")
		assertTrue(capped, capped.startsWith("The database has at least ${DatabaseTools.MAX_ROWS} tables;"))
		answerWith("SELECT name" to QueryResult(listOf("name"), names.take(DatabaseTools.MAX_ROWS - 1), 0, false))
		val exact = ok("get_stats", db()).getString("row_counts_skipped")
		assertTrue(exact, exact.startsWith("The database has ${DatabaseTools.MAX_ROWS - 1} tables;"))
	}

	@Test fun integrityCheckIsQuickUnlessAskedForFull() {
		engine.appConnections += conn(1, "/data/a.db")
		answerWith("PRAGMA" to QueryResult(listOf("quick_check"), listOf(listOf("ok")), 0, false))
		val quick = ok("integrity_check", db())
		assertTrue(quick.getBoolean("ok"))
		assertEquals("quick_check", quick.getString("check"))
		ok("integrity_check", db().put("full", true))
		assertEquals(listOf("PRAGMA quick_check", "PRAGMA integrity_check"), seenSql)
		answerWith("PRAGMA" to QueryResult(listOf("c"), listOf(listOf("row 1 missing from index i1")), 0, false))
		assertFalse(ok("integrity_check", db()).getBoolean("ok"))
	}

	@Test fun getEventsReturnsCursorDroppedAndTurnsOnExpansion() {
		assertFalse(engine.clientConnected)
		engine.addEvent(EventKind.STATEMENT, "select 1")
		engine.addEvent(EventKind.PROFILE, "select 1", durationNanos = 99)
		engine.addEvent(EventKind.STATEMENT, "select 2")
		engine.dropOldest(1)
		val t = tools
		val first = ok("get_events", t = t)
		assertTrue(engine.clientConnected)
		assertEquals(1, first.getInt("dropped"))
		assertEquals(3, first.getInt("next"))
		val events = first.getJSONArray("events")
		assertEquals(2, events.length())
		assertEquals("profile", events.getJSONObject(0).getString("kind"))
		assertEquals(99, events.getJSONObject(0).getInt("duration_nanos"))
		assertTrue(events.getJSONObject(1).isNull("duration_nanos"))
		val second = ok("get_events", JSONObject().put("after", 3), t)
		assertEquals(0, second.getJSONArray("events").length())
		assertEquals(0, second.getInt("dropped"))
		assertEquals(3, second.getInt("next"))
		t.onServerStopped()
		assertFalse(engine.clientConnected)
	}

	private fun seqs(r: JSONObject) = r.getJSONArray("events").let { a -> (0 until a.length()).map { a.getJSONObject(it).getLong("seq") } }

	private fun addStatements(count: Int) = repeat(count) { engine.addEvent(EventKind.STATEMENT, "select ${it + 1}") }

	@Test fun getEventsCarriesTheRunAndNoResetForACaughtUpCursor() {
		addStatements(3)
		val t = tools
		val first = ok("get_events", t = t)
		assertEquals(engine.runId, first.getString("run"))
		assertFalse(first.getBoolean("reset"))
		assertEquals(listOf(1L, 2L, 3L), seqs(first))
		val caughtUp = ok("get_events", JSONObject().put("after", first.getLong("next")).put("run", first.getString("run")), t)
		assertFalse("after equal to the newest seq is a reader that kept up", caughtUp.getBoolean("reset"))
		assertEquals(emptyList<Long>(), seqs(caughtUp))
		assertEquals(3, caughtUp.getInt("next"))
		engine.addEvent(EventKind.STATEMENT, "select 4")
		val more = ok("get_events", JSONObject().put("after", 3).put("run", engine.runId), t)
		assertFalse(more.getBoolean("reset"))
		assertEquals(listOf(4L), seqs(more))
		t.onServerStopped()
	}

	@Test fun getEventsWithAnotherRunReadsFromTheStartAndSaysReset() {
		addStatements(3)
		val t = tools
		val r = ok("get_events", JSONObject().put("after", 3).put("run", "a-run-of-an-earlier-process"), t)
		assertTrue(r.getBoolean("reset"))
		assertEquals("read as if after were 0", listOf(1L, 2L, 3L), seqs(r))
		assertEquals(engine.runId, r.getString("run"))
		t.onServerStopped()
	}

	@Test fun getEventsWithACursorPastTheNewestSaysResetEvenWithoutARun() {
		addStatements(3)
		val t = tools
		val r = ok("get_events", JSONObject().put("after", 4), t)
		assertTrue(r.getBoolean("reset"))
		assertEquals(listOf(1L, 2L, 3L), seqs(r))
		assertEquals(3, r.getInt("next"))
		t.onServerStopped()
	}

	@Test fun anAgentWithACursorFromBeforeAnAppRestartIsToldAndReadsTheNewProcessFromTheStart() {
		addStatements(5)
		val t = tools
		val before = ok("get_events", t = t)
		val oldRun = before.getString("run")
		assertEquals(5, before.getInt("next"))
		engine.restartProcess("run-2")
		addStatements(2)
		val withRun = ok("get_events", JSONObject().put("after", 5).put("run", oldRun), t)
		assertTrue(withRun.getBoolean("reset"))
		assertEquals("run-2", withRun.getString("run"))
		assertEquals(listOf(1L, 2L), seqs(withRun))
		val withoutRun = ok("get_events", JSONObject().put("after", 5), t)
		assertTrue("past the new process's newest seq (2)", withoutRun.getBoolean("reset"))
		assertEquals(listOf(1L, 2L), seqs(withoutRun))
		val next = ok("get_events", JSONObject().put("after", withRun.getLong("next")).put("run", withRun.getString("run")), t)
		assertFalse("the cursor and run from the reset are valid", next.getBoolean("reset"))
		assertEquals(emptyList<Long>(), seqs(next))
		t.onServerStopped()
	}

	@Test fun getEventsRunMustBeAString() {
		val r = runTool(tools, "get_events", JSONObject().put("run", 5))
		assertEquals("run must be a string.", errorText(r))
	}

	@Test fun getEventsDescribesRunAndReset() {
		val tool = tools.tools().single { it.name == "get_events" }
		assertTrue(tool.description.contains("Pass the returned next as after and the returned run as run"))
		assertTrue(tool.description.contains("reset true means the app restarted"))
		assertTrue(
			tool
				.describe()
				.getJSONObject("inputSchema")
				.getJSONObject("properties")
				.has("run"),
		)
	}

	@Test fun aServerConnectionIsDroppedWhenTheAppRecreatesTheDatabase() {
		engine.appConnections += conn(1, "/data/a.db")
		val t = tools
		ok("get_stats", db(), t)
		assertEquals(1, engine.openedServerConnections.size)
		// the app closed connection 1, deleted the file and opened a new one: no overlap with {1}
		engine.appConnections.clear()
		engine.appConnections += conn(2, "/data/a.db")
		ok("get_stats", db(), t)
		assertEquals(2, engine.openedServerConnections.size)
		assertEquals(1, engine.closedServerConnections)
	}

	@Test fun aServerConnectionIsKeptWhileASeenAppConnectionRemains() {
		engine.appConnections += conn(1, "/data/a.db")
		val t = tools
		ok("get_stats", db(), t)
		engine.appConnections += conn(2, "/data/a.db")
		ok("get_stats", db(), t)
		engine.appConnections.removeAll { it.id == 2L }
		ok("get_stats", db(), t)
		assertEquals(1, engine.openedServerConnections.size)
		assertEquals(0, engine.closedServerConnections)
	}

	@Test fun aServerConnectionIsDroppedWhenNoAppConnectionHasThePathAnyMore() {
		engine.appConnections += conn(1, "/data/a.db")
		val t = tools
		ok("get_stats", db(), t)
		engine.appConnections.clear()
		engine.appConnections += conn(3, "/data/b.db")
		assertTrue(runTool(t, "get_stats", db()).getBoolean("isError")) // refused: no longer a known path
		assertEquals(1, engine.closedServerConnections)
	}

	@Test fun boundValuesStopBeingRecordedSixtySecondsAfterTheLastGetEvents() {
		val t = tools
		ok("get_events", t = t)
		assertTrue(engine.clientConnected)
		now += 59_000
		ok("list_connections", t = t)
		assertTrue("still within 60 s", engine.clientConnected)
		ok("get_events", t = t) // renews the 60 s
		now += 59_000
		ok("list_connections", t = t)
		assertTrue(engine.clientConnected)
		now += 2_000
		ok("list_connections", t = t)
		assertFalse("60 s after the last get_events", engine.clientConnected)
		ok("get_events", t = t)
		assertTrue("the next get_events turns it on again", engine.clientConnected)
		now += 61_000
		t.expireIdleClient() // what the timer calls when no tool call comes
		assertFalse(engine.clientConnected)
		t.onServerStopped()
	}

	private class FakeIdleScheduler : IdleScheduler {
		class Check(
			val task: Runnable,
		) {
			var done = false
		}

		val checks = mutableListOf<Check>()
		val pending get() = checks.filter { !it.done }

		override fun schedule(
			delayMs: Long,
			task: Runnable,
		): () -> Unit {
			val check = Check(task)
			checks += check
			return { check.done = true }
		}

		fun runPending() {
			val check = pending.single()
			check.done = true
			check.task.run()
		}
	}

	@Test fun onlyOneIdleCheckIsPendingAcrossExpireAndReconnectCycles() {
		val scheduler = FakeIdleScheduler()
		val t = DatabaseTools(engine, dir, { now }, scheduler)
		ok("get_events", t = t)
		assertEquals(1, scheduler.pending.size)
		repeat(3) { cycle ->
			// the check finds the client still connected and reschedules itself
			now += 15_000
			scheduler.runPending()
			assertTrue(engine.clientConnected)
			assertEquals("cycle $cycle: after the check rescheduled", 1, scheduler.pending.size)
			// a tool call expires the client, and get_events reconnects while a check is still pending
			now += 61_000
			ok("list_connections", t = t)
			assertFalse(engine.clientConnected)
			ok("get_events", t = t)
			assertTrue(engine.clientConnected)
			assertEquals("cycle $cycle: after the reconnect", 1, scheduler.pending.size)
		}
		now += 61_000
		scheduler.runPending() // expires the client and does not reschedule
		assertFalse(engine.clientConnected)
		assertEquals(0, scheduler.pending.size)
		ok("get_events", t = t)
		assertEquals(1, scheduler.pending.size)
		t.onServerStopped()
		assertEquals("stopping the server cancels the check", 0, scheduler.pending.size)
	}

	@Test fun aCheckThatWasRunningWhenTheServerStoppedDoesNotReschedule() {
		val scheduler = FakeIdleScheduler()
		val t = DatabaseTools(engine, dir, { now }, scheduler)
		ok("get_events", t = t)
		val running = scheduler.pending.single()
		t.onServerStopped()
		running.task.run()
		assertEquals(0, scheduler.pending.size)
	}

	@Test fun executeRunsOnAWritableConnectionApartFromTheReadOnlyOne() {
		engine.appConnections += conn(1, "/data/a.db")
		val ran = mutableListOf<Pair<String, List<Any?>>>()
		engine.executeAnswer = { _, sql, args ->
			ran += sql to args
			ExecuteResult(changes = 3)
		}
		val t = tools
		ok("query", db().put("sql", "select 1"), t)
		val r = ok("execute", db().put("sql", "update t set a = ? where b = ?").put("args", JSONArray().put("x").put(2)), t)
		assertEquals(3, r.getInt("changes"))
		assertEquals(listOf("update t set a = ? where b = ?" to listOf<Any?>("x", 2L)), ran)
		ok("execute", db().put("sql", "delete from t"), t)
		ok("query", db().put("sql", "select 1"), t)
		// one of each, each kept: a read never runs on the writable connection
		assertEquals(listOf("/data/a.db" to false, "/data/a.db" to true), engine.openedServerConnections)
	}

	@Test fun executeReportsChangesPastTwoToTheFiftyThirdAsAString() {
		engine.appConnections += conn(1, "/data/a.db")
		engine.executeAnswer = { _, _, _ -> ExecuteResult(changes = 1L shl 60) }
		assertEquals((1L shl 60).toString(), ok("execute", db().put("sql", "delete from t")).getString("changes"))
	}

	@Test fun executeErrorsCarryTheSqliteCode() {
		engine.appConnections += conn(1, "/data/a.db")
		engine.executeAnswer = { _, _, _ -> throw DebugEngineException(DebugEngineException.SQLITE_AUTH, "not authorized") }
		val r = runTool(tools, "execute", db().put("sql", "begin"))
		assertTrue(r.getBoolean("isError"))
		assertEquals(23, r.getJSONObject("structuredContent").getInt("sqlite_code"))
	}

	@Test fun executeChecksItsArgumentsBeforeTheEngine() {
		engine.appConnections += conn(1, "/data/a.db")
		assertTrue(runTool(tools, "execute", db()).getBoolean("isError"))
		assertTrue(runTool(tools, "execute", db().put("sql", "x").put("args", "nope")).getBoolean("isError"))
		assertTrue(runTool(tools, "execute", db().put("sql", "x").put("args", JSONArray().put(JSONObject()))).getBoolean("isError"))
		assertEquals(0, engine.openAttempts)
	}

	@Test fun theWritableConnectionIsDroppedWhenTheAppRecreatesTheDatabaseAndClosedWhenTheServerStops() {
		engine.appConnections += conn(1, "/data/a.db")
		val t = tools
		ok("execute", db().put("sql", "delete from t"), t)
		engine.appConnections.clear()
		engine.appConnections += conn(2, "/data/a.db")
		ok("execute", db().put("sql", "delete from t"), t)
		// execute also opens the read-only connection, to look for Room (ADFA-7299): one of each, twice
		assertEquals(listOf(false, true, false, true), engine.openedServerConnections.map { it.second })
		assertEquals(2, engine.closedServerConnections)
		t.onServerStopped()
		assertEquals(4, engine.closedServerConnections)
	}

	@Test fun executeDescribesWhatItRefusesItsTimeLimitAndThatTheAppIsNotTold() {
		val d = tools.tools().first { it.name == "execute" }.description
		for (text in listOf(
			"committed at once and cannot be undone",
			"BEGIN, COMMIT, ROLLBACK and SAVEPOINT are refused",
			"does not write is refused: use query",
			"RETURNING are not returned",
			"stopped and rolled back after 1.5 s",
			"in every journal mode",
			"may stay stale",
			"Functions the app adds to its own connections",
		)) {
			assertTrue("$text: $d", d.contains(text))
		}
		assertTrue(tools.tools().first { it.name == "query" }.description.contains("Writes are refused: use execute."))
	}

	/** /data/room.db has room_master_table; /data/a.db does not. Records what execute ran. */
	private fun roomSetup(): MutableList<String> {
		engine.appConnections += listOf(conn(1, "/data/a.db"), conn(2, "/data/room.db"))
		engine.answer = { path, _, sql, _ ->
			seenSql += sql
			val room = path == "/data/room.db" && sql.contains("room_master_table")
			QueryResult(listOf("1"), if (room) listOf(listOf(1L)) else emptyList(), 0, false)
		}
		val ran = mutableListOf<String>()
		engine.executeAnswer = { path, sql, _ ->
			ran += "$path: $sql"
			ExecuteResult(1)
		}
		return ran
	}

	@Test fun aRoomDatabaseRefusesTheFirstWriteWithAWarningAndRunsNothing() {
		val ran = roomSetup()
		val r = runTool(tools, "execute", db("/data/room.db").put("sql", "delete from note"))
		assertTrue(r.getBoolean("isError"))
		val text = r.getJSONArray("content").getJSONObject(0).getString("text")
		for (part in listOf("Nothing was run", "Room database", "stale data", "acknowledge_room true", "Tell the user")) {
			assertTrue("$part: $text", text.contains(part))
		}
		val details = r.getJSONObject("structuredContent")
		assertTrue(details.getBoolean("room"))
		assertEquals("acknowledge_room", details.getString("acknowledge_with"))
		assertTrue(ran.isEmpty())
		// it looked on the read-only connection, and opened no writable one
		assertEquals(listOf("/data/room.db" to false), engine.openedServerConnections)
		// asked again without the flag, it refuses again
		assertTrue(runTool(tools, "execute", db("/data/room.db").put("sql", "delete from note")).getBoolean("isError"))
		assertTrue(ran.isEmpty())
	}

	@Test fun anAcknowledgedRoomWriteRunsAndLaterWritesRunWithoutAsking() {
		val ran = roomSetup()
		val t = tools
		ok("execute", db("/data/room.db").put("sql", "delete from note where id = 1").put("acknowledge_room", true), t)
		ok("execute", db("/data/room.db").put("sql", "delete from note where id = 2"), t)
		ok("execute", db("/data/room.db").put("sql", "delete from note where id = 3").put("acknowledge_room", false), t)
		assertEquals(
			listOf(
				"/data/room.db: delete from note where id = 1",
				"/data/room.db: delete from note where id = 2",
				"/data/room.db: delete from note where id = 3",
			),
			ran,
		)
		// once acknowledged, the database is not looked at again
		assertEquals(1, seenSql.count { it.contains("room_master_table") })
	}

	@Test fun anAcknowledgementCoversOnlyItsOwnDatabaseAndOnlyThisServerProcess() {
		engine.appConnections += conn(3, "/data/room2.db")
		val ran = roomSetup()
		engine.answer = { _, _, sql, _ -> QueryResult(listOf("1"), if (sql.contains("room_master_table")) listOf(listOf(1L)) else emptyList(), 0, false) }
		val t = tools
		ok("execute", db("/data/room.db").put("sql", "delete from note").put("acknowledge_room", true), t)
		assertTrue(runTool(t, "execute", db("/data/room2.db").put("sql", "delete from note")).getBoolean("isError"))
		// a new DatabaseTools, as in a new app process, asks again
		assertTrue(runTool(tools, "execute", db("/data/room.db").put("sql", "delete from note")).getBoolean("isError"))
		assertEquals(listOf("/data/room.db: delete from note"), ran)
	}

	@Test fun aDatabaseWithoutRoomNeverAsks() {
		val ran = roomSetup()
		val t = tools
		ok("execute", db("/data/a.db").put("sql", "delete from t"), t)
		ok("execute", db("/data/a.db").put("sql", "delete from t").put("acknowledge_room", true), t)
		assertEquals(listOf("/data/a.db: delete from t", "/data/a.db: delete from t"), ran)
	}

	@Test fun acknowledgeRoomMustBeABooleanAndIsCheckedBeforeTheEngine() {
		roomSetup()
		val r = runTool(tools, "execute", db("/data/room.db").put("sql", "delete from note").put("acknowledge_room", "yes"))
		assertTrue(r.getBoolean("isError"))
		assertEquals(0, engine.openAttempts)
	}

	@Test fun executeDescribesTheRoomAcknowledgement() {
		val execute = tools.tools().first { it.name == "execute" }.describe()
		assertTrue(execute.getString("description").contains("room_master_table"))
		assertTrue(execute.getString("description").contains("acknowledge_room true"))
		val prop = execute.getJSONObject("inputSchema").getJSONObject("properties").getJSONObject("acknowledge_room")
		assertEquals("boolean", prop.getString("type"))
		assertFalse(execute.getJSONObject("inputSchema").getJSONArray("required").toString().contains("acknowledge_room"))
	}

	@Test fun stoppingTheServerClosesServerConnections() {
		engine.appConnections += conn(1, "/data/a.db")
		val t = tools
		ok("get_stats", db(), t)
		ok("get_stats", db(), t)
		assertEquals(1, engine.openedServerConnections.size)
		t.onServerStopped()
		ok("get_stats", db(), t)
		assertEquals(2, engine.openedServerConnections.size)
	}
}
