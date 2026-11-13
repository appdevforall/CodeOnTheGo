package org.appdevforall.sqlitedebugger.mcp

import org.appdevforall.sqlitedebugger.engine.AppConnection
import org.appdevforall.sqlitedebugger.engine.DebugEngineException
import org.appdevforall.sqlitedebugger.engine.EventKind
import org.appdevforall.sqlitedebugger.engine.FakeDebugEngine
import org.appdevforall.sqlitedebugger.engine.QueryResult
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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

	@Test fun everyToolIsReadOnly() {
		val list = tools.tools()
		assertEquals(
			listOf(
				"list_databases",
				"list_connections",
				"get_schema",
				"query",
				"explain_query_plan",
				"get_stats",
				"integrity_check",
				"get_events",
			),
			list.map { it.name },
		)
		list.forEach { assertTrue(it.name, it.describe().getJSONObject("annotations").getBoolean("readOnlyHint")) }
	}

	@Test fun listDatabasesGroupsByPathAndSkipsEmpty() {
		engine.appConnections += listOf(conn(1, "/data/a.db"), conn(2, "/data/a.db"), conn(3, ""), conn(4, "/data/b.db"))
		val dbs = ok("list_databases").getJSONArray("databases")
		assertEquals(2, dbs.length())
		assertEquals("/data/a.db", dbs.getJSONObject(0).getString("path"))
		assertEquals(2, dbs.getJSONObject(0).getInt("connections"))
		assertTrue(dbs.getJSONObject(1).getBoolean("available"))
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
	}

	@Test fun emptyPathOfAnInMemoryConnectionIsNotOpenable() {
		engine.appConnections += conn(1, "")
		assertTrue(runTool(tools, "get_stats", db("")).getBoolean("isError"))
		assertTrue(engine.openedServerConnections.isEmpty())
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
		assertEquals(true, DatabaseTools.encodeValue(ByteArray(1024).also { }).let { (it as JSONObject).getBoolean("truncated") })
		assertEquals(1.5, DatabaseTools.encodeValue(1.5))
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
		assertTrue(seenSql.single().contains("sqlite_schema"))
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
	}

	@Test fun getStatsSkipsCountsFromTenTables() {
		engine.appConnections += conn(1, "/data/a.db")
		answerWith("SELECT name" to QueryResult(listOf("name"), (1..10).map { listOf("t$it") }, 0, false))
		val r = ok("get_stats", db())
		assertFalse(r.has("row_counts"))
		assertTrue(r.has("row_counts_skipped"))
		assertTrue(seenSql.none { it.startsWith("SELECT COUNT") })
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
