package org.appdevforall.sqlitedebugger.mcp

import org.appdevforall.sqlitedebugger.engine.AppConnection
import org.appdevforall.sqlitedebugger.engine.DebugEngineException
import org.appdevforall.sqlitedebugger.engine.FakeDebugEngine
import org.appdevforall.sqlitedebugger.engine.QueryResult
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * get_template_variables (ADFA-7302). The engine's answers are the rows SQLite 3.53 gave for the same SQL on this
 * schema (a Room-like database with an FTS4 table kept in step by Room's triggers):
 *
 * ```
 * CREATE TABLE room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT);
 * CREATE TABLE p(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL DEFAULT 'x', g INT GENERATED ALWAYS AS (id*2));
 * CREATE TABLE c(a INT, b INT, pid INT REFERENCES p(id) ON DELETE CASCADE, PRIMARY KEY(a,b)) WITHOUT ROWID;
 * CREATE INDEX ci ON c(pid, a+1);
 * CREATE UNIQUE INDEX pn ON p(name) WHERE name<>'';
 * CREATE VIRTUAL TABLE f USING fts4(content='p', name);
 * CREATE TRIGGER room_fts_content_sync_f_BEFORE_UPDATE BEFORE UPDATE ON p BEGIN DELETE FROM f WHERE docid=OLD.rowid; END;
 * CREATE VIEW v AS SELECT * FROM p;
 * ```
 */
class TemplateVariablesTest {
	private val engine =
		FakeDebugEngine().also {
			it.appConnections += AppConnection(1, "/data/app.db", false, false, 1, 5)
			it.appConnections += AppConnection(2, "/data/androidx.work.workdb", false, false, 1, 5)
		}
	private var environment: JSONObject? = null
	private val tools = DatabaseTools(engine, null, { 1_000_000L }, environment = { environment })

	private fun call(args: JSONObject): JSONObject {
		val protocol = McpProtocol(tools.tools(), "test", { "s" }, 4)
		val init =
			JSONObject()
				.put("jsonrpc", "2.0")
				.put("id", 1)
				.put("method", "initialize")
				.put("params", JSONObject().put("protocolVersion", "2025-06-18"))
		val session = protocol.post(init.toString(), null, null).headers[McpProtocol.SESSION_HEADER]!!
		val request =
			JSONObject()
				.put("jsonrpc", "2.0")
				.put("id", 2)
				.put("method", "tools/call")
				.put("params", JSONObject().put("name", "get_template_variables").put("arguments", args))
		val r = JSONObject(protocol.post(request.toString(), session, null).body!!).getJSONObject("result")
		assertFalse(r.toString(), r.getBoolean("isError"))
		return r.getJSONObject("structuredContent")
	}

	private fun rows(
		vararg rows: List<Any?>,
		columns: Int = rows.firstOrNull()?.size ?: 1,
	) = QueryResult(List(columns) { "c$it" }, rows.toList(), 0, false)

	private val roomSchema: (String, Boolean, String, List<Any?>) -> QueryResult = { path, _, sql, _ ->
		when {
			path != "/data/app.db" -> {
				if (sql.startsWith("PRAGMA")) rows(listOf(0L)) else rows()
			}

			sql == "PRAGMA user_version" -> {
				rows(listOf(3L))
			}

			sql == "PRAGMA journal_mode" -> {
				rows(listOf("wal"))
			}

			sql == "PRAGMA page_size" -> {
				rows(listOf(4096L))
			}

			sql == "PRAGMA page_count" -> {
				rows(listOf(12L))
			}

			sql.startsWith("SELECT name, type, wr FROM pragma_table_list") -> {
				rows(
					listOf("c", "table", 1L),
					listOf("f", "virtual", 0L),
					listOf("f_docsize", "shadow", 0L),
					listOf("p", "table", 0L),
					listOf("room_master_table", "table", 0L),
					listOf("sqlite_schema", "table", 0L),
					listOf("sqlite_sequence", "table", 0L),
				)
			}

			sql.startsWith("SELECT t.name, c.name") -> {
				rows(
					listOf("c", "a", "INT", 1L, null, 1L, 0L),
					listOf("c", "b", "INT", 1L, null, 2L, 0L),
					listOf("c", "pid", "INT", 0L, null, 0L, 0L),
					listOf("f", "name", "", 0L, null, 0L, 0L),
					listOf("f", "f", "", 0L, null, 0L, 1L),
					listOf("f", "docid", "", 0L, null, 0L, 1L),
					listOf("f_docsize", "docid", "INTEGER", 0L, null, 1L, 0L),
					listOf("f_docsize", "size", "BLOB", 0L, null, 0L, 0L),
					listOf("p", "id", "INTEGER", 0L, null, 1L, 0L),
					listOf("p", "name", "TEXT", 1L, "'x'", 0L, 0L),
					listOf("p", "g", "INT", 0L, null, 0L, 2L),
					listOf("room_master_table", "id", "INTEGER", 0L, null, 1L, 0L),
					listOf("room_master_table", "identity_hash", "TEXT", 0L, null, 0L, 0L),
					listOf("sqlite_sequence", "name", "", 0L, null, 0L, 0L),
				)
			}

			sql.startsWith("SELECT t.name, il.name") -> {
				rows(
					listOf("c", "ci", 0L, "c", 0L, "pid"),
					listOf("c", "ci", 0L, "c", 0L, null),
					listOf("c", "sqlite_autoindex_c_1", 1L, "pk", 0L, "a"),
					listOf("c", "sqlite_autoindex_c_1", 1L, "pk", 0L, "b"),
					listOf("p", "pn", 1L, "c", 1L, "name"),
				)
			}

			sql.startsWith("SELECT t.name, f.id") -> {
				rows(listOf("c", 0L, "p", "pid", "id", "NO ACTION", "CASCADE"))
			}

			sql.startsWith("SELECT type, name, tbl_name") -> {
				rows(
					listOf("table", "f", "f", "CREATE VIRTUAL TABLE f USING fts4(content='p', name)"),
					listOf("trigger", "room_fts_content_sync_f_BEFORE_UPDATE", "p", null),
					listOf("view", "v", "v", null),
				)
			}

			sql.startsWith("SELECT identity_hash FROM room_master_table") -> {
				rows(listOf("abc123"))
			}

			else -> {
				rows()
			}
		}
	}

	private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }

	private fun JSONArray.values() = (0 until length()).map { get(it) }

	@Test fun readsEveryListOfARoomDatabase() {
		engine.answer = roomSchema
		val db = call(JSONObject().put("database", "/data/app.db")).getJSONArray("databases").getJSONObject(0)
		assertEquals("app.db", db.getString("name"))
		assertTrue(db.isNull("library"))
		assertEquals(3, db.getInt("user_version"))
		assertEquals("wal", db.getString("journal_mode"))
		assertEquals(4096, db.getInt("page_size"))
		assertEquals(12, db.getInt("page_count"))

		val tables = db.getJSONArray("tables").objects().associateBy { it.getString("name") }
		// sqlite_ tables are left out; the shadow table is listed with its type.
		assertEquals(listOf("c", "f", "f_docsize", "p", "room_master_table"), tables.keys.toList())
		assertEquals("shadow", tables.getValue("f_docsize").getString("type"))

		val c = tables.getValue("c")
		assertTrue(c.getBoolean("without_rowid"))
		assertEquals(listOf("a", "b"), c.getJSONArray("primary_key").values())
		val ci = c.getJSONArray("indexes").objects().first { it.getString("name") == "ci" }
		// An expression in an index is a null column.
		assertEquals(listOf("pid", JSONObject.NULL), ci.getJSONArray("columns").values())
		assertEquals(
			"pk",
			c
				.getJSONArray("indexes")
				.objects()
				.first { it.getString("name") == "sqlite_autoindex_c_1" }
				.getString("origin"),
		)
		val fk = c.getJSONArray("foreign_keys").getJSONObject(0)
		assertEquals("p", fk.getString("table"))
		assertEquals(listOf("pid"), fk.getJSONArray("from").values())
		assertEquals("CASCADE", fk.getString("on_delete"))

		// A virtual table's hidden columns are left out; a generated column is kept and marked.
		assertEquals(1, tables.getValue("f").getInt("column_count"))
		val p = tables.getValue("p")
		assertEquals(3, p.getInt("column_count"))
		val name = p.getJSONArray("columns").getJSONObject(1)
		assertEquals("TEXT", name.getString("declared_type"))
		assertTrue(name.getBoolean("not_null"))
		assertEquals("'x'", name.getString("default"))
		assertTrue(p.getJSONArray("columns").getJSONObject(2).getBoolean("generated"))
		assertTrue(p.getJSONArray("indexes").getJSONObject(0).getBoolean("partial"))

		assertEquals(listOf("f"), db.getJSONArray("fts_tables").values())
		assertEquals("v", db.getJSONArray("views").getJSONObject(0).getString("name"))
		assertEquals("p", db.getJSONArray("triggers").getJSONObject(0).getString("table"))
		assertTrue(db.getBoolean("has_sqlite_sequence"))

		val room = db.getJSONObject("room")
		assertEquals(3, room.getInt("schema_version"))
		assertEquals("abc123", room.getString("identity_hash"))
		assertEquals(listOf("room_master_table"), room.getJSONArray("own_tables").values())
		assertEquals(listOf("room_fts_content_sync_f_BEFORE_UPDATE"), room.getJSONArray("fts_sync_triggers").values())
	}

	@Test fun aDatabaseWithoutRoomMasterTableHasNoRoomAndALibraryIsNamed() {
		engine.answer = roomSchema
		val out = call(JSONObject())
		val byName = out.getJSONArray("databases").objects().associateBy { it.getString("name") }
		assertEquals(setOf("app.db", "androidx.work.workdb"), byName.keys)
		val work = byName.getValue("androidx.work.workdb")
		assertEquals("androidx.work", work.getString("library"))
		assertTrue(work.isNull("room"))
		assertFalse(work.getBoolean("has_sqlite_sequence"))
		assertEquals(0, out.getJSONArray("errors").length())
		// Only the identity hash read touches a table's rows, and only for Room's own table.
		assertTrue(engine.queries.none { it.first.contains("count(", ignoreCase = true) })
	}

	@Test fun appAndDeviceComeFromTheEnvironmentAndAreNullWithoutOne() {
		engine.answer = roomSchema
		assertTrue(call(JSONObject()).isNull("app"))
		environment =
			JSONObject()
				.put("app", JSONObject().put("package", "com.example"))
				.put("device", JSONObject().put("api_level", 34))
		val out = call(JSONObject())
		assertEquals("com.example", out.getJSONObject("app").getString("package"))
		assertEquals(34, out.getJSONObject("device").getInt("api_level"))
	}

	@Test fun aDatabaseThatFailsIsAnErrorAndTheOthersStillCome() {
		engine.answer = { path, w, sql, a ->
			if (path == "/data/androidx.work.workdb" && sql.startsWith("SELECT name, type")) {
				throw DebugEngineException(11, "database disk image is malformed")
			}
			roomSchema(path, w, sql, a)
		}
		val out = call(JSONObject())
		assertEquals(1, out.getJSONArray("databases").length())
		val error = out.getJSONArray("errors").getJSONObject(0)
		assertEquals("/data/androidx.work.workdb", error.getString("database"))
		assertTrue(error.getString("error"), error.getString("error").contains("malformed"))
	}

	@Test fun aListPastTheLimitIsCutAndSaysSo() {
		val many = (0..DatabaseTools.MAX_TEMPLATE_ROWS).map { listOf("t%05d".format(it), "table", 0L) }
		engine.answer = { path, w, sql, a ->
			if (sql.startsWith("SELECT name, type, wr")) {
				QueryResult(listOf("name", "type", "wr"), many, 0, false)
			} else {
				roomSchema(path, w, sql, a)
			}
		}
		val out = call(JSONObject().put("database", "/data/app.db"))
		assertTrue(out.getBoolean("truncated"))
		assertEquals(
			DatabaseTools.MAX_TEMPLATE_ROWS,
			out
				.getJSONArray("databases")
				.getJSONObject(0)
				.getJSONArray("tables")
				.length(),
		)
	}

	@Test fun aListOfExactlyTheLimitIsWhole() {
		val exact = (1..DatabaseTools.MAX_TEMPLATE_ROWS).map { listOf("t%05d".format(it), "table", 0L) }
		engine.answer = { path, w, sql, a ->
			if (sql.startsWith("SELECT name, type, wr")) {
				QueryResult(listOf("name", "type", "wr"), exact, 0, false)
			} else {
				roomSchema(path, w, sql, a)
			}
		}
		assertFalse(call(JSONObject().put("database", "/data/app.db")).getBoolean("truncated"))
	}
}
