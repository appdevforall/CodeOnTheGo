package org.appdevforall.sqlitedebugger.mcp

import org.appdevforall.sqlitedebugger.engine.AppConnection
import org.appdevforall.sqlitedebugger.engine.FakeDebugEngine
import org.appdevforall.sqlitedebugger.engine.IndexSuggestions
import org.appdevforall.sqlitedebugger.engine.QueryResult
import org.appdevforall.sqlitedebugger.engine.StatementSuggestion
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The sqlite3 shell tools (ADFA-7301): suggest_indexes, lint_foreign_keys, dump, export, and get_stats' .dbinfo fields. */
class ShellToolsTest {
	private val engine = FakeDebugEngine().also { it.appConnections += AppConnection(1, "/data/a.db", false, false, 1, 5) }
	private val tools = DatabaseTools(engine, null, { 1_000_000L })
	private val seen = mutableListOf<Pair<String, List<Any?>>>()

	private fun call(
		name: String,
		args: JSONObject,
	): JSONObject {
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
				.put("params", JSONObject().put("name", name).put("arguments", args))
		return JSONObject(protocol.post(request.toString(), session, null).body!!).getJSONObject("result")
	}

	private fun ok(
		name: String,
		args: JSONObject = JSONObject(),
	): JSONObject {
		val r = call(name, args.put("database", "/data/a.db"))
		assertFalse(r.toString(), r.getBoolean("isError"))
		return r.getJSONObject("structuredContent")
	}

	private fun error(
		name: String,
		args: JSONObject,
	): String {
		val r = call(name, args.put("database", "/data/a.db"))
		assertTrue(r.toString(), r.getBoolean("isError"))
		return r.getJSONArray("content").getJSONObject(0).getString("text")
	}

	private fun result(
		columns: List<String>,
		vararg rows: List<Any?>,
	) = QueryResult(columns, rows.toList(), 0, false)

	/** Answers by the first matching SQL prefix; anything else gets no rows. */
	private fun answer(vararg byPrefix: Pair<String, (List<Any?>) -> QueryResult>) {
		engine.answer = { _, _, sql, args ->
			seen += sql to args
			byPrefix.firstOrNull { sql.startsWith(it.first) }?.second?.invoke(args) ?: QueryResult(emptyList(), emptyList(), 0, false)
		}
	}

	private fun JSONArray.strings() = (0 until length()).map { getString(it) }

	@Test fun suggestIndexesSplitsTheReportIntoLines() {
		engine.suggestAnswer = { path, sql ->
			assertEquals("/data/a.db", path)
			assertEquals("SELECT * FROM t WHERE a = 1", sql)
			IndexSuggestions(
				"CREATE INDEX t_idx_1 ON t(a); -- stat1: 10 1\n",
				listOf(StatementSuggestion(sql, "CREATE INDEX t_idx_1 ON t(a);\n", "SEARCH t USING INDEX t_idx_1 (a=?)\n")),
			)
		}
		val r = ok("suggest_indexes", JSONObject().put("sql", "SELECT * FROM t WHERE a = 1"))
		val st = r.getJSONArray("statements").getJSONObject(0)
		assertEquals("SELECT * FROM t WHERE a = 1", st.getString("sql"))
		assertEquals(listOf("CREATE INDEX t_idx_1 ON t(a);"), st.getJSONArray("indexes").strings())
		assertEquals(listOf("SEARCH t USING INDEX t_idx_1 (a=?)"), st.getJSONArray("plan").strings())
		assertEquals(listOf("CREATE INDEX t_idx_1 ON t(a); -- stat1: 10 1"), r.getJSONArray("candidates").strings())
	}

	@Test fun suggestIndexesSamplesOnlyWhenAsked() {
		ok("suggest_indexes", JSONObject().put("sql", "SELECT 1"))
		assertEquals(0, engine.lastSample)
		ok("suggest_indexes", JSONObject().put("sql", "SELECT 1").put("sample", 25))
		assertEquals(25, engine.lastSample)
		assertTrue(error("suggest_indexes", JSONObject().put("sql", "SELECT 1").put("sample", 101)).contains("between 0 and 100"))
		assertTrue(error("suggest_indexes", JSONObject().put("sql", "SELECT 1").put("sample", -1)).contains("between 0 and 100"))
		val description = tools.tools().single { it.name == "suggest_indexes" }.description
		for (text in listOf("sample 0", "1.5 s", "explain_query_plan")) assertTrue(text, description.contains(text))
	}

	@Test fun suggestIndexesWithNothingToSuggestHasEmptyLists() {
		engine.suggestAnswer = { _, sql -> IndexSuggestions(null, listOf(StatementSuggestion(sql, null, null))) }
		val st = ok("suggest_indexes", JSONObject().put("sql", "SELECT 1")).getJSONArray("statements").getJSONObject(0)
		assertEquals(0, st.getJSONArray("indexes").length())
		assertEquals(0, st.getJSONArray("plan").length())
		error("suggest_indexes", JSONObject())
	}

	@Test fun lintForeignKeysReportsWhichKeysAnIndexCovers() {
		answer(
			"SELECT s.name, f.id" to {
				result(
					listOf("name", "id", "table", "from", "to"),
					listOf("lines", 0L, "orders", "order_id", "id"),
					listOf("notes", 0L, "orders", "order_id", null),
					listOf("pairs", 0L, "pk2", "a", "x"),
					listOf("pairs", 0L, "pk2", "b", "y"),
					listOf("pairs", 1L, "orders", "id", "id"),
				)
			},
			"SELECT name FROM pragma_table_info" to { args ->
				assertEquals(listOf<Any?>("orders"), args)
				result(listOf("name"), listOf("id"))
			},
			"EXPLAIN QUERY PLAN SELECT 1 FROM \"lines\"" to {
				result(listOf("id", "parent", "notused", "detail"), listOf(3L, 0L, 0L, "SCAN lines"))
			},
			"EXPLAIN QUERY PLAN SELECT 1 FROM \"notes\"" to {
				result(
					listOf("id", "parent", "notused", "detail"),
					listOf(3L, 0L, 0L, "SEARCH notes USING COVERING INDEX notes_order (order_id=?)"),
				)
			},
			"EXPLAIN QUERY PLAN SELECT 1 FROM \"pairs\" WHERE \"pairs\".\"a\"=? AND" to {
				result(
					listOf("id", "parent", "notused", "detail"),
					listOf(3L, 0L, 0L, "SEARCH pairs USING COVERING INDEX pairs_ab (a=? AND b=?)"),
				)
			},
			"EXPLAIN QUERY PLAN SELECT 1 FROM \"pairs\" WHERE \"pairs\".\"id\"" to {
				result(listOf("id", "parent", "notused", "detail"), listOf(3L, 0L, 0L, "SEARCH pairs USING INTEGER PRIMARY KEY (rowid=?)"))
			},
		)
		val r = ok("lint_foreign_keys")
		val keys = r.getJSONArray("foreign_keys")
		assertEquals(4, keys.length())
		val lines = keys.getJSONObject(0)
		assertFalse(lines.getBoolean("covered"))
		assertEquals("CREATE INDEX \"lines_order_id\" ON \"lines\"(\"order_id\");", lines.getString("create_index"))
		val notes = keys.getJSONObject(1)
		assertTrue(notes.getBoolean("covered"))
		assertFalse(notes.has("create_index"))
		assertEquals(
			"a key naming no parent column takes the parent's primary key",
			listOf("id"),
			notes.getJSONArray("parent_columns").strings(),
		)
		val pairs = keys.getJSONObject(2)
		assertEquals(listOf("a", "b"), pairs.getJSONArray("child_columns").strings())
		assertEquals(listOf("x", "y"), pairs.getJSONArray("parent_columns").strings())
		assertTrue("a two-column key is one key", pairs.getBoolean("covered"))
		assertTrue("an INTEGER PRIMARY KEY covers it", keys.getJSONObject(3).getBoolean("covered"))
		assertEquals(1, r.getInt("uncovered"))
		val eqp = seen.single { it.first.startsWith("EXPLAIN QUERY PLAN SELECT 1 FROM \"pairs\" WHERE \"pairs\".\"a\"") }
		assertEquals("EXPLAIN QUERY PLAN SELECT 1 FROM \"pairs\" WHERE \"pairs\".\"a\"=? AND \"pairs\".\"b\"=?", eqp.first)
		assertEquals(listOf(null, null), eqp.second)
	}

	@Test fun lintLeavesOutTheLastKeyWhenTheListIsCut() {
		// 1001 columns: key a has 997 of them, key b has 4, and the engine returns the first 1000, so b has only 3
		val columns = listOf("name", "id", "table", "from", "to")
		val all = (1..997).map { listOf("a", 0L, "p", "c$it", "k$it") } + (1..4).map { listOf("b", 0L, "p", "d$it", "m$it") }
		answer(
			"SELECT s.name, f.id" to { QueryResult(columns, all, 0, false) },
			"EXPLAIN QUERY PLAN SELECT 1 FROM" to {
				result(listOf("id", "parent", "notused", "detail"), listOf(3L, 0L, 0L, "SEARCH t USING INTEGER PRIMARY KEY (rowid=?)"))
			},
		)
		val r = ok("lint_foreign_keys")
		assertTrue(r.getBoolean("truncated"))
		val keys = r.getJSONArray("foreign_keys")
		assertEquals(1, keys.length())
		assertEquals("a", keys.getJSONObject(0).getString("child_table"))
		assertEquals(997, keys.getJSONObject(0).getJSONArray("child_columns").length())
		assertTrue("no check ran on the partial key", seen.none { it.first.startsWith("EXPLAIN QUERY PLAN SELECT 1 FROM \"b\"") })
	}

	@Test fun lintGlobFailsWhenTheIndexCoversOnlySomeColumns() {
		assertTrue(ShellFormat.globMatches("SEARCH t USING COVERING INDEX*(*=? AND *=?)", "SEARCH t USING COVERING INDEX i (a=? AND b=?)"))
		assertFalse(ShellFormat.globMatches("SEARCH t USING COVERING INDEX*(*=? AND *=?)", "SEARCH t USING COVERING INDEX i (a=?)"))
		assertFalse(ShellFormat.globMatches("SEARCH t USING COVERING INDEX*(*=?)", "SCAN t"))
		assertTrue("* also spans parentheses", ShellFormat.globMatches("a*b", "a(x)b"))
		assertTrue("regex characters are literal", ShellFormat.globMatches("SEARCH t.x+ USING*", "SEARCH t.x+ USING y"))
		assertFalse(ShellFormat.globMatches("SEARCH t.x+ USING*", "SEARCH tax USING y"))
	}

	private fun dumpSchema(
		tables: List<List<Any?>>,
		others: List<List<Any?>> = emptyList(),
		rows: (table: String, offset: Int) -> List<String> = { _, _ -> emptyList() },
		xinfo: Map<String, List<List<Any?>>> = emptyMap(),
		withoutRowid: Set<String> = emptySet(),
		tablesCut: Set<Long> = emptySet(),
		othersCut: Set<Long> = emptySet(),
		shadows: List<String> = emptyList(),
	) {
		engine.answer = { _, _, sql, args ->
			seen += sql to args
			when {
				sql.startsWith("SELECT name, sql FROM sqlite_master") -> {
					QueryResult(
						listOf("name", "sql"),
						tables.filter { args.isEmpty() || it[0] in args },
						tablesCut.size,
						false,
						tablesCut,
					)
				}

				sql.startsWith("SELECT sql FROM sqlite_master") -> {
					QueryResult(listOf("sql"), others, othersCut.size, false, othersCut)
				}

				sql.startsWith("SELECT name, hidden FROM pragma_table_xinfo") -> {
					QueryResult(listOf("name", "hidden"), xinfo[args[0]] ?: listOf(listOf("a", 0L)), 0, false)
				}

				sql.startsWith("SELECT name FROM pragma_table_list") -> {
					// type 'shadow', named after the virtual table (args[0]) and "_"
					QueryResult(listOf("name"), shadows.filter { it.startsWith("${args[0]}_") }.map { listOf(it) }, 0, false)
				}

				sql.startsWith("SELECT wr FROM pragma_table_list") -> {
					QueryResult(listOf("wr"), listOf(listOf(if (args[0] in withoutRowid) 1L else 0L)), 0, false)
				}

				sql.startsWith("SELECT 'INSERT INTO ") -> {
					val table = Regex("FROM \"([^\"]*)\"").findAll(sql).last().groupValues[1]
					QueryResult(listOf("s"), rows(table, 0).map { listOf(it) }, 0, false)
				}

				else -> {
					QueryResult(emptyList(), emptyList(), 0, false)
				}
			}
		}
	}

	@Test fun dumpWritesTheShellsOrder() {
		dumpSchema(
			tables =
				listOf(
					listOf("t", "CREATE TABLE t(a)"),
					listOf("sqlite_sequence", "CREATE TABLE sqlite_sequence(name,seq)"),
					listOf("sqlite_stat4", "CREATE TABLE sqlite_stat4(tbl,idx,neq,nlt,ndlt,sample)"),
				),
			others = listOf(listOf("CREATE INDEX ta ON t(a)"), listOf("CREATE VIEW v AS SELECT a FROM t")),
			rows = { table, _ ->
				if (table == "t") {
					listOf("INSERT INTO \"t\" VALUES(1);", "INSERT INTO \"t\" VALUES('x');")
				} else {
					listOf("INSERT INTO \"sqlite_sequence\" VALUES('t',2);")
				}
			},
		)
		val r = ok("dump")
		assertEquals(
			listOf(
				"PRAGMA foreign_keys=OFF;",
				"BEGIN TRANSACTION;",
				"CREATE TABLE t(a);",
				"INSERT INTO \"t\" VALUES(1);",
				"INSERT INTO \"t\" VALUES('x');",
				"DELETE FROM sqlite_sequence;",
				"INSERT INTO \"sqlite_sequence\" VALUES('t',2);",
				"CREATE INDEX ta ON t(a);",
				"CREATE VIEW v AS SELECT a FROM t;",
				"COMMIT;",
			),
			r.getJSONArray("sql").strings(),
		)
		assertTrue(r.getBoolean("complete"))
		assertTrue(r.isNull("next_cursor"))
		assertEquals(0, r.getJSONArray("truncated_statements").length())
		val select = seen.first { it.first.startsWith("SELECT 'INSERT INTO ") }.first
		assertEquals("SELECT 'INSERT INTO \"t\" VALUES(' || quote(\"a\") || ');' FROM \"t\" ORDER BY _rowid_", select)
	}

	@Test fun dumpLeavesGeneratedColumnsOutAndKeepsAWithoutRowidTableUnordered() {
		dumpSchema(
			tables = listOf(listOf("g", "CREATE TABLE g(a, b AS (a + 1), c)")),
			xinfo = mapOf("g" to listOf(listOf("a", 0L), listOf("b", 2L), listOf("c", 0L))),
			withoutRowid = setOf("g"),
		)
		ok("dump")
		val select = seen.first { it.first.startsWith("SELECT 'INSERT INTO ") }.first
		assertEquals("SELECT 'INSERT INTO \"g\"(\"a\",\"c\") VALUES(' || quote(\"a\") || ',' || quote(\"c\") || ');' FROM \"g\"", select)
	}

	@Test fun dumpWritesAVirtualTableIntoTheSchemaAsTheShellDoes() {
		dumpSchema(
			tables =
				listOf(
					listOf("docs", "CREATE VIRTUAL TABLE docs USING fts5(body)"),
					listOf("docs_data", "CREATE TABLE 'docs_data'(id INTEGER PRIMARY KEY, block BLOB)"),
				),
		)
		val sql = ok("dump").getJSONArray("sql").strings()
		assertEquals("PRAGMA writable_schema=ON;", sql[2])
		assertEquals(
			"INSERT INTO sqlite_schema(type,name,tbl_name,rootpage,sql) " +
				"VALUES('table','docs','docs',0,'CREATE VIRTUAL TABLE docs USING fts5(body)');",
			sql[3],
		)
		assertEquals("CREATE TABLE 'docs_data'(id INTEGER PRIMARY KEY, block BLOB);", sql[4])
		assertEquals(listOf("PRAGMA writable_schema=OFF;", "COMMIT;"), sql.takeLast(2))
	}

	@Test fun dumpOfOneTableAsksOnlyForItsObjects() {
		dumpSchema(tables = listOf(listOf("t", "CREATE TABLE t(a)"), listOf("u", "CREATE TABLE u(b)")))
		val sql = ok("dump", JSONObject().put("table", "u")).getJSONArray("sql").strings()
		assertEquals(listOf("PRAGMA foreign_keys=OFF;", "BEGIN TRANSACTION;", "CREATE TABLE u(b);", "COMMIT;"), sql)
		assertTrue(seen.single { it.first.startsWith("SELECT sql FROM sqlite_master") }.first.contains("AND tbl_name IN (?)"))
		assertEquals("No table named \"zz\".", error("dump", JSONObject().put("table", "zz")))
	}

	@Test fun dumpOfAVirtualTableIncludesItsShadowTables() {
		dumpSchema(
			tables =
				listOf(
					listOf("docs", "CREATE VIRTUAL TABLE docs USING fts5(body)"),
					listOf("docs_data", "CREATE TABLE 'docs_data'(id INTEGER PRIMARY KEY, block BLOB)"),
					listOf("docs_idx", "CREATE TABLE 'docs_idx'(segid, term, pgno, PRIMARY KEY(segid, term)) WITHOUT ROWID"),
					listOf("other_data", "CREATE TABLE other_data(a)"),
				),
			others = listOf(listOf("CREATE INDEX docs_data_i ON docs_data(id)")),
			shadows = listOf("docs_data", "docs_idx", "other_data"),
			rows = { table, _ -> listOf("INSERT INTO \"$table\" VALUES(1);") },
		)
		val sql = ok("dump", JSONObject().put("table", "docs")).getJSONArray("sql").strings()
		assertEquals(
			listOf(
				"PRAGMA foreign_keys=OFF;",
				"BEGIN TRANSACTION;",
				"PRAGMA writable_schema=ON;",
				"INSERT INTO sqlite_schema(type,name,tbl_name,rootpage,sql) " +
					"VALUES('table','docs','docs',0,'CREATE VIRTUAL TABLE docs USING fts5(body)');",
				"CREATE TABLE 'docs_data'(id INTEGER PRIMARY KEY, block BLOB);",
				"INSERT INTO \"docs_data\" VALUES(1);",
				"CREATE TABLE 'docs_idx'(segid, term, pgno, PRIMARY KEY(segid, term)) WITHOUT ROWID;",
				"INSERT INTO \"docs_idx\" VALUES(1);",
				"CREATE INDEX docs_data_i ON docs_data(id);",
				"PRAGMA writable_schema=OFF;",
				"COMMIT;",
			),
			sql,
		)
		val others = seen.single { it.first.startsWith("SELECT sql FROM sqlite_master") }
		assertEquals(listOf<Any?>("docs", "docs_data", "docs_idx"), others.second)
		assertTrue(seen.any { it.first.startsWith("SELECT name FROM pragma_table_list") && it.first.contains("type = 'shadow'") })
	}

	@Test fun dumpOfAPlainTableDoesNotLookForShadowTables() {
		dumpSchema(tables = listOf(listOf("t", "CREATE TABLE t(a)")), shadows = listOf("t_data"))
		ok("dump", JSONObject().put("table", "t"))
		assertTrue(seen.none { it.first.contains("'shadow'") })
	}

	@Test fun dumpPagesAtTheStatementLimitAndResumesFromTheCursor() {
		val all = (1..1500).map { "INSERT INTO \"t\" VALUES($it);" }
		dumpSchema(tables = listOf(listOf("t", "CREATE TABLE t(a)")))
		engine.answer.let { base ->
			engine.answer = { path, writable, sql, args ->
				if (sql.startsWith("SELECT 'INSERT INTO ")) {
					QueryResult(listOf("s"), all.map { listOf(it) }, 0, false)
				} else {
					base(path, writable, sql, args)
				}
			}
		}
		val first = ok("dump")
		val firstSql = first.getJSONArray("sql").strings()
		assertEquals(DatabaseTools.MAX_ROWS, firstSql.size)
		assertFalse(first.getBoolean("complete"))
		assertEquals("INSERT INTO \"t\" VALUES(997);", firstSql.last())
		val second = ok("dump", JSONObject().put("cursor", first.getString("next_cursor")))
		val secondSql = second.getJSONArray("sql").strings()
		assertEquals("INSERT INTO \"t\" VALUES(998);", secondSql.first())
		assertEquals("COMMIT;", secondSql.last())
		assertTrue(second.getBoolean("complete"))
		assertEquals(1500 + 4, firstSql.size + secondSql.size)
		assertTrue(error("dump", JSONObject().put("cursor", "x")).contains("cursor"))
	}

	@Test fun dumpListsACutStatement() {
		dumpSchema(tables = listOf(listOf("t", "CREATE TABLE t(a)")))
		engine.answer.let { base ->
			engine.answer = { path, writable, sql, args ->
				if (sql.startsWith("SELECT 'INSERT INTO ")) {
					QueryResult(
						listOf("s"),
						listOf(listOf("INSERT INTO \"t\" VALUES(1);"), listOf("INSERT INTO \"t\" VALUES('xx")),
						1,
						false,
						setOf(1L),
					)
				} else {
					base(path, writable, sql, args)
				}
			}
		}
		val r = ok("dump")
		assertEquals(
			listOf(4),
			(0 until r.getJSONArray("truncated_statements").length()).map { r.getJSONArray("truncated_statements").getInt(it) },
		)
	}

	@Test fun dumpReadsSchemaSqlWithTheStatementLimitAndListsACutOne() {
		// cut cells are row * columns + column: the table query has 2 columns, the others query 1
		dumpSchema(
			tables = listOf(listOf("t", "CREATE TABLE t(a, b"), listOf("u", "CREATE TABLE u(b)")),
			others = listOf(listOf("CREATE INDEX ok ON u(b)"), listOf("CREATE VIEW v AS SELECT 'x")),
			tablesCut = setOf(1L),
			othersCut = setOf(1L),
		)
		val r = ok("dump")
		val sql = r.getJSONArray("sql").strings()
		assertEquals("CREATE TABLE t(a, b;", sql[2])
		assertEquals("CREATE VIEW v AS SELECT 'x;", sql[sql.size - 2])
		assertEquals(listOf(2, sql.size - 2), (0 until 2).map { r.getJSONArray("truncated_statements").getInt(it) })
		assertEquals(2, r.getJSONArray("truncated_statements").length())
		val schemaQueries = engine.queries.filter { it.first.contains("FROM sqlite_master") }
		assertEquals(2, schemaQueries.size)
		for ((sqlText, limits) in schemaQueries) {
			assertEquals(sqlText, DatabaseTools.DUMP_STATEMENT_BYTES, limits.maxTextBytes)
		}
	}

	@Test fun dumpListsACutVirtualTableStatement() {
		dumpSchema(tables = listOf(listOf("docs", "CREATE VIRTUAL TABLE docs USING fts5(bo")), tablesCut = setOf(1L))
		val r = ok("dump")
		val cut = r.getJSONArray("truncated_statements")
		assertEquals(listOf(3), (0 until cut.length()).map { cut.getInt(it) })
	}

	@Test fun dumpSaysWhenTheSchemaPassesTheByteBudgetNotTheTableLimit() {
		val big = "x".repeat(3 * 1024 * 1024)
		engine.answer = { _, _, sql, _ ->
			if (sql.startsWith("SELECT name, sql")) {
				QueryResult(listOf("name", "sql"), listOf(listOf("a", big), listOf("b", big)), 0, false)
			} else {
				QueryResult(listOf("sql"), listOf(listOf(big), listOf(big)), 0, false)
			}
		}
		val message = error("dump", JSONObject())
		assertTrue(message, message.contains("MiB") && !message.contains("more than ${DatabaseTools.MAX_ROWS} tables"))
		engine.answer = { _, _, sql, _ ->
			if (sql.startsWith("SELECT name, sql")) {
				QueryResult(listOf("name", "sql"), listOf(listOf("a", "CREATE TABLE a(x)")), 0, false)
			} else {
				QueryResult(listOf("sql"), listOf(listOf(big), listOf(big)), 0, false)
			}
		}
		val others = error("dump", JSONObject())
		assertTrue(others, others.contains("MiB") && !others.contains("more than ${DatabaseTools.MAX_ROWS} indexes"))
	}

	@Test fun dumpStillSaysMoreThanTheTableLimitWhenRowsRunOut() {
		engine.answer = { _, _, sql, _ ->
			if (sql.startsWith("SELECT name, sql")) {
				QueryResult(listOf("name", "sql"), (1..1001).map { listOf("t$it", "CREATE TABLE t$it(a)") }, 0, false)
			} else {
				QueryResult(listOf("sql"), emptyList(), 0, false)
			}
		}
		assertTrue(error("dump", JSONObject()).contains("more than ${DatabaseTools.MAX_ROWS} tables"))
	}

	@Test fun exportWritesCsvAsTheShellDoes() {
		answer(
			"SELECT" to {
				result(
					listOf("id", "name,x", "b"),
					listOf(1L, "a\"b", byteArrayOf(1, -1)),
					listOf(2.5, "", null),
					listOf(Double.POSITIVE_INFINITY, "l1\nl2", "plain"),
				)
			},
		)
		val r = ok("export", JSONObject().put("sql", "SELECT").put("format", "csv"))
		assertEquals("id,\"name,x\",b\r\n1,\"a\"\"b\",01ff\r\n2.5,\"\",\r\n1e999,\"l1\nl2\",plain\r\n", r.getString("text"))
		assertEquals(3, r.getInt("row_count"))
		val page2 = ok("export", JSONObject().put("sql", "SELECT").put("format", "csv").put("offset", 1))
		assertFalse("no header past the first page", page2.getString("text").startsWith("id,"))
		val noHeader = ok("export", JSONObject().put("sql", "SELECT").put("format", "csv").put("header", false))
		assertTrue(noHeader.getString("text").startsWith("1,"))
	}

	@Test fun exportWritesInsertStatementsThatReadBackTheSame() {
		answer(
			"SELECT" to {
				result(
					listOf("a", "b", "c", "d"),
					listOf(1L, 2.0, "it's", byteArrayOf(0, 16)),
					listOf(null, Double.NaN, "", byteArrayOf()),
				)
			},
		)
		val r = ok("export", JSONObject().put("sql", "SELECT").put("format", "insert").put("table", "my \"t\""))
		assertEquals(
			"INSERT INTO \"my \"\"t\"\"\" VALUES(1,2.0,'it''s',X'0010');\nINSERT INTO \"my \"\"t\"\"\" VALUES(NULL,NULL,'',X'');\n",
			r.getString("text"),
		)
		assertEquals(
			"INSERT INTO \"table\" VALUES(1,2.0,'it''s',X'0010');",
			ok("export", JSONObject().put("sql", "SELECT").put("format", "insert")).getString("text").lines().first(),
		)
		assertTrue(error("export", JSONObject().put("sql", "SELECT").put("format", "json")).contains("csv or insert"))
	}

	@Test fun exportUsesItsOwnValueLimitAndPassesArgs() {
		var limits: Any? = null
		engine.answer = { _, _, _, args ->
			limits = args
			result(listOf("a"), listOf(1L))
		}
		ok("export", JSONObject().put("sql", "SELECT :x").put("format", "csv").put("args", JSONObject().put("x", 5)))
		assertEquals(listOf<Any?>(5L), limits)
		assertEquals(listOf("x"), engine.lastArgNames)
	}

	@Test fun sqlLiteralsAndCsvFieldsRoundTrip() {
		assertEquals("0.1", ShellFormat.sqlLiteral(0.1))
		assertEquals("1.0E300", ShellFormat.sqlLiteral(1e300))
		assertEquals("-1e999", ShellFormat.sqlLiteral(Double.NEGATIVE_INFINITY))
		assertEquals("NULL", ShellFormat.sqlLiteral(Double.NaN))
		assertEquals("'a''b'", ShellFormat.sqlLiteral("a'b"))
		assertEquals("X'7f'", ShellFormat.sqlLiteral(byteArrayOf(127)))
		assertEquals("9223372036854775807", ShellFormat.sqlLiteral(Long.MAX_VALUE))
		assertEquals("", ShellFormat.csvField(null))
		assertEquals("\"\"", ShellFormat.csvField(""))
		assertEquals("a\rb".let { "\"$it\"" }, ShellFormat.csvField("a\rb"))
	}

	@Test fun getStatsAddsTheDbinfoFields() {
		val page = ByteArray(4096)
		page[18] = 2
		page[19] = 2
		page[20] = 12
		page[27] = 9 // file change counter 9
		page[47] = 4 // schema format 4
		page[50] = 1 // default cache size 256
		page[96] = 0
		page[97] = 0x2f.toByte()
		page[98] = 0xc5.toByte()
		page[99] = 0x2c // 3_130_668: 3.49.1 is 3049001, any value round-trips
		answer(
			"PRAGMA user_version" to { result(listOf("v"), listOf(7L)) },
			"PRAGMA encoding" to { result(listOf("e"), listOf("UTF-8")) },
			"SELECT type, count(*)" to { result(listOf("type", "n", "size"), listOf("table", 3L, 120.0), listOf("index", 1L, 30.0)) },
			"SELECT data FROM sqlite_dbpage" to { result(listOf("data"), listOf(page)) },
		)
		val r = ok("get_stats")
		assertEquals(7, r.getInt("user_version"))
		assertEquals("UTF-8", r.getString("encoding"))
		assertEquals(3, r.getJSONObject("schema_objects").getInt("table"))
		assertEquals(0, r.getJSONObject("schema_objects").getInt("view"))
		assertEquals(150, r.getInt("schema_size"))
		val header = r.getJSONObject("header")
		assertEquals(2, header.getInt("write_format"))
		assertEquals(12, header.getInt("reserved_bytes"))
		assertEquals(9, header.getInt("file_change_counter"))
		assertEquals(4, header.getInt("schema_format"))
		assertEquals(256, header.getInt("default_cache_size"))
		assertEquals(0x2fc52cL, header.getLong("software_version"))
		assertFalse(r.has("header_unavailable"))
	}

	@Test fun getStatsSaysWhyTheHeaderIsMissingWithoutSqliteDbpage() {
		engine.answer = { _, _, sql, _ ->
			if (sql.contains("sqlite_dbpage")) {
				throw org.appdevforall.sqlitedebugger.engine
					.DebugEngineException(1, "no such table: sqlite_dbpage")
			}
			QueryResult(emptyList(), emptyList(), 0, false)
		}
		val r = ok("get_stats")
		assertFalse(r.has("header"))
		assertTrue(r.getString("header_unavailable"), r.getString("header_unavailable").contains("no such table: sqlite_dbpage"))
		assertNull(ShellFormat.headerFields(ByteArray(99)))
	}
}
