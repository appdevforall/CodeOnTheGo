package com.itsaky.androidide.plugins.manager.services.sqltemplate

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.plugins.services.SqlTemplateException
import com.itsaky.androidide.plugins.services.SqlTemplateFunction
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Supplier

class SqlTemplateRendererTest {
	private fun render(
		text: String,
		variables: Map<String, Any?> = emptyMap(),
		functions: Map<String, SqlTemplateFunction> = emptyMap(),
		timeoutMillis: Long = 5_000,
	) = SqlTemplateRenderer.render("t.sql.peb", text, variables, functions, timeoutMillis)

	private fun failure(
		text: String,
		variables: Map<String, Any?> = emptyMap(),
		functions: Map<String, SqlTemplateFunction> = emptyMap(),
		timeoutMillis: Long = 5_000,
	): String = assertThrows(SqlTemplateException::class.java) { render(text, variables, functions, timeoutMillis) }.message!!

	@Test
	fun `uses the project templates' delimiters and newline trimming`() {
		val text = "SELECT\${% if wide %}\n *\${% else %}\n id\${% endif %}\nFROM t \${# a comment #}\n;"
		// A closing %} or #} eats the line break after it, as in a project template.
		assertThat(render(text, mapOf("wide" to true))).isEqualTo("SELECT *FROM t ;")
		assertThat(render("\${{ a }} \${{ b }}", mapOf("a" to 1L, "b" to "x"))).isEqualTo("1 x")
	}

	@Test
	fun `autoescaping is off`() {
		assertThat(render("\${{ s }}", mapOf("s" to "a < b && c > 'd'"))).isEqualTo("a < b && c > 'd'")
	}

	@Test
	fun `sql_ident quotes a name and doubles its quotes`() {
		assertThat(render("SELECT * FROM \${{ t | sql_ident }}", mapOf("t" to "we\"ird table")))
			.isEqualTo("SELECT * FROM \"we\"\"ird table\"")
		assertThat(failure("\${{ n | sql_ident }}", mapOf("n" to 3L))).contains("sql_ident at line 1 needs a string, not a Long")
		assertThat(failure("\${{ n | sql_ident }}", mapOf("n" to null))).contains("needs a string, not null")
		assertThat(failure("\${{ n | sql_ident }}", mapOf("n" to "a\u0000b"))).contains("NUL")
	}

	@Test
	fun `sql_literal writes literals that read back the same`() {
		val variables =
			mapOf(
				"s" to "it's",
				"n" to null,
				"l" to -42L,
				"d" to 2.5,
				"whole" to 2.0,
				"big" to 1e300,
				"yes" to true,
				"no" to false,
			)
		val text =
			"\${{ s | sql_literal }} \${{ n | sql_literal }} \${{ l | sql_literal }} \${{ d | sql_literal }} " +
				"\${{ whole | sql_literal }} \${{ big | sql_literal }} \${{ yes | sql_literal }} \${{ no | sql_literal }}"
		assertThat(render(text, variables)).isEqualTo("'it''s' NULL -42 2.5 2.0 1.0E300 1 0")
		assertThat(failure("\${{ x | sql_literal }}", mapOf("x" to Double.NaN))).contains("NaN has no SQL literal")
		assertThat(failure("\${{ x | sql_literal }}", mapOf("x" to "a\u0000"))).contains("NUL")
		assertThat(failure("\${{ x | sql_literal }}", mapOf("x" to listOf(1L)))).contains("cannot quote")
	}

	@Test
	fun `an unknown variable is an error naming it and its line`() {
		assertThat(failure("SELECT 1\n\${{ missing }}")).contains("missing")
		assertThat(failure("SELECT 1\n\${{ missing }}")).contains("line 2")
	}

	@Test
	fun `a supplier is called only when the template reads it, and once`() {
		val calls = AtomicInteger()
		val counted = Supplier { calls.incrementAndGet() * 10L }
		assertThat(render("\${% if false %}\${{ lazy }}\${% endif %}x", mapOf("lazy" to counted))).isEqualTo("x")
		assertThat(calls.get()).isEqualTo(0)
		assertThat(render("\${{ lazy }} \${{ lazy }}", mapOf("lazy" to counted))).isEqualTo("10 10")
		assertThat(calls.get()).isEqualTo(1)
		// in a map and a list too, and still once when read twice through them
		val nestedCalls = AtomicInteger()
		val nested =
			mapOf(
				"db" to
					mapOf(
						"tables" to
							listOf(
								Supplier {
									nestedCalls.incrementAndGet()
									"users"
								},
							),
					),
			)
		assertThat(render("\${{ db.tables[0] | sql_ident }} \${{ db.tables[0] }}", nested)).isEqualTo("\"users\" users")
		assertThat(nestedCalls.get()).isEqualTo(1)
		// reading one element of a list, even twice, reads none of the others
		val other = AtomicInteger()
		val list =
			mapOf(
				"l" to
					listOf(
						Supplier { "a" },
						Supplier {
							other.incrementAndGet()
							"b"
						},
					),
			)
		assertThat(render("\${{ l[0] }}\${{ l[0] }}", list)).isEqualTo("aa")
		assertThat(other.get()).isEqualTo(0)
	}

	@Test
	fun `a function runs only when called, with its arguments in order`() {
		val seen = mutableListOf<List<Any?>>()
		val rowCount =
			SqlTemplateFunction { args ->
				seen += args
				7L
			}
		val functions = mapOf("row_count" to rowCount)
		assertThat(render("\${% if false %}\${{ row_count('a', 't') }}\${% endif %}none", functions = functions)).isEqualTo("none")
		assertThat(seen).isEmpty()
		assertThat(render("\${{ row_count('a.db', 'users') + 1 }}", functions = functions)).isEqualTo("8")
		assertThat(seen).containsExactly(listOf("a.db", "users"))
	}

	@Test
	fun `a function's value can be a list of maps the template loops over`() {
		val sample = SqlTemplateFunction { listOf(mapOf("id" to 1L), mapOf("id" to 2L)) }
		val text = "\${% for r in sample_rows() %}\${{ r.id }},\${% endfor %}"
		assertThat(render(text, functions = mapOf("sample_rows" to sample))).isEqualTo("1,2,")
	}

	@Test
	fun `a failing function stops the render with its name and error`() {
		val fails = SqlTemplateFunction { throw IllegalStateException("no such table: t") }
		val message = failure("SELECT\n\${{ row_count('a', 't') }}", functions = mapOf("row_count" to fails))
		assertThat(message).contains("row_count(...) at line 2 failed: no such table: t")
	}

	@Test
	fun `the time limit covers the whole render and interrupts a function`() {
		val interrupted = CountDownLatch(1)
		val slow =
			SqlTemplateFunction {
				try {
					Thread.sleep(10_000)
				} catch (e: InterruptedException) {
					interrupted.countDown()
					throw e
				}
				1L
			}
		val started = System.nanoTime()
		val message = failure("\${{ slow() }}", functions = mapOf("slow" to slow), timeoutMillis = 200)
		assertThat(message).contains("did not finish within 200 ms")
		assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(5_000L)
		assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue()
	}

	@Test
	fun `bad names, values and limits are refused before rendering`() {
		val fn = SqlTemplateFunction { null }
		assertThrows(IllegalArgumentException::class.java) { render("x", functions = mapOf("row-count" to fn)) }
		assertThrows(IllegalArgumentException::class.java) { render("x", functions = mapOf("range" to fn)) }
		assertThrows(IllegalArgumentException::class.java) { render("x", functions = mapOf("sql_literal" to fn)) }
		assertThrows(IllegalArgumentException::class.java) { render("x", mapOf("when" to java.util.Date())) }
		assertThrows(IllegalArgumentException::class.java) { render("x", mapOf("m" to mapOf(1 to "a"))) }
		assertThrows(IllegalArgumentException::class.java) { render("x", timeoutMillis = 0) }
	}

	@Test
	fun `a supplier or function that returns another type fails the render`() {
		assertThat(failure("\${{ v }}", mapOf("v" to Supplier { Any() }))).contains("cannot be a template value")
		assertThat(failure("\${{ f() }}", functions = mapOf("f" to SqlTemplateFunction { Any() }))).contains("cannot be a template value")
	}

	@Test
	fun `a render past the size limit fails`() {
		val text = "\${% for i in range(1, 5000) %}\${{ chunk }}\${% endfor %}"
		assertThat(failure(text, mapOf("chunk" to "x".repeat(1024)))).contains("Tried to write more than")
	}

	private fun renderThreads() = Thread.getAllStackTraces().keys.filter { it.name == "sql-template-render" && it.isAlive }

	private fun awaitNoRenderThread(): Boolean {
		val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
		while (System.nanoTime() < until) {
			if (renderThreads().isEmpty()) return true
			Thread.sleep(20)
		}
		return false
	}

	@Test
	fun `a busy loop that writes nothing is stopped by the time limit and its thread ends`() {
		val message =
			failure("\${% for i in range(1, 100000) %}\${% for j in range(1, 100000) %}\${% endfor %}\${% endfor %}", timeoutMillis = 200)
		assertThat(message).contains("did not finish within 200 ms")
		assertThat(awaitNoRenderThread()).isTrue()
	}

	@Test
	fun `a loop over a map or list still works, with its loop variables`() {
		val text = "\${% for k in m %}\${{ k.key }}=\${{ k.value }}:\${{ loop.index }}/\${{ loop.length }},\${% endfor %}"
		assertThat(render(text, mapOf("m" to mapOf("a" to 1L, "b" to 2L)))).isEqualTo("a=1:0/2,b=2:1/2,")
		assertThat(render("\${% for x in l %}\${{ x }}\${% else %}none\${% endfor %}", mapOf("l" to emptyList<Any>()))).isEqualTo("none")
	}

	@Test
	fun `range past the cap fails with a clear error, and a normal range is unchanged`() {
		val message = failure("\${% for i in range(1, 9999999999) %}\${% endfor %}")
		assertThat(message).contains("the most is 100000")
		assertThat(failure("\${% for i in range(0, 100000) %}\${% endfor %}")).contains("the most is 100000")
		assertThat(render("\${% for i in range(1, 100000) %}\${% endfor %}ok")).isEqualTo("ok")
		assertThat(render("\${% for i in range(1, 5) %}\${{ i }}\${% endfor %}")).isEqualTo("12345")
		assertThat(render("\${% for i in range(10, 1, -3) %}\${{ i }},\${% endfor %}")).isEqualTo("10,7,4,1,")
		assertThat(render("\${% for i in range(5, 1) %}\${{ i }}\${% endfor %}none")).isEqualTo("none")
		assertThat(render("\${% for c in range('a', 'e', 2) %}\${{ c }}\${% endfor %}")).isEqualTo("ace")
		assertThat(render("\${% for i in range(1, 9, increment=4) %}\${{ i }},\${% endfor %}")).isEqualTo("1,5,9,")
		assertThat(failure("\${% for i in range(1, 5, 0) %}\${% endfor %}")).contains("different than 0")
		assertThat(failure("\${% for i in range(1, 'z') %}\${% endfor %}")).contains("Number or String")
		assertThat(failure("\${% for i in range(-9223372036854775807, 9223372036854775807) %}\${% endfor %}")).contains("the most is")
	}
}
