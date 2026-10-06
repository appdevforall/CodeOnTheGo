package com.itsaky.androidide.lsp.java.refactor

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * The emitted text (R15): the two edits and their order, the call-site forms, and the whitespace the
 * file already uses.
 *
 * Code-action edits bypass the editor's auto-indent, so what is emitted here is what the user sees.
 */
@RunWith(JUnit4::class)
class ExtractMethodEditTest {
	private val fixtures = mutableListOf<JavacFixture>()

	@After
	fun tearDown() = fixtures.forEach(JavacFixture::close)

	@Test
	fun `the two edits are in descending document order`() {
		val f = fixture("""	int m(int a, int b) {${'\n'}		return a + b;${'\n'}	}""")
		val plan = f.methodPlanAfter("a +")

		val rewrites = buildExtractMethodRewrites(plan.fileText, plan.candidates.first(), "sum")!!

		assertThat(rewrites).hasSize(2)
		assertThat(rewrites[0].span.start).isGreaterThan(rewrites[1].span.start)
		// The insertion leads: the anchor member contains the region, so its end is always past it.
		assertThat(rewrites[0].span.length).isEqualTo(0)
	}

	@Test
	fun `the new method is separated by a blank line at the anchor's indentation`() {
		val f = fixture("""	int m(int a, int b) {${'\n'}		return a + b;${'\n'}	}""")

		val out = f.applyMethod(f.methodPlanAfter("a +"), "sum")

		assertThat(out).contains("\t}\n\n\tprivate int sum(int a, int b) {\n\t\treturn a + b;\n\t}")
	}

	@Test
	fun `a statement range call site is a statement`() {
		val f = fixture("""	void m(int a) {${'\n'}		use(a);${'\n'}	}""")

		val out = f.applyMethod(f.methodPlanOver("use(a);"), "report")

		assertThat(out).contains("\t\treport(a);\n")
		assertWithMessage(out).that(compiles(out)).isTrue()
	}

	@Test
	fun `a multi-statement body keeps its relative indentation`() {
		val f =
			fixture(
				"""	void m(int a) {${'\n'}		if (a > 0) {${'\n'}			use(a);${'\n'}		}${'\n'}	}""",
			)

		val out = f.applyMethod(f.methodPlanOver("if (a > 0) {${'\n'}			use(a);${'\n'}		}"), "report")

		assertThat(out).contains("\tprivate void report(int a) {\n\t\tif (a > 0) {\n\t\t\tuse(a);\n\t\t}\n\t}")
		assertWithMessage(out).that(compiles(out)).isTrue()
	}

	@Test
	fun `a space-indented file receives space-indented output`() {
		val f =
			JavacFixture(
				"class Fixture {\n    int m(int a, int b) {\n        return a + b;\n    }\n}",
			).also { fixtures += it }

		val out = f.applyMethod(f.methodPlanAfter("a +"), "sum")

		assertThat(out).contains("\n    private int sum(int a, int b) {\n        return a + b;\n    }")
		assertThat(out).doesNotContain("\t")
	}

	@Test
	fun `a CRLF file keeps CRLF`() {
		val f =
			JavacFixture(
				"class Fixture {\r\n\tint m(int a, int b) {\r\n\t\treturn a + b;\r\n\t}\r\n}",
			).also { fixtures += it }

		val out = f.applyMethod(f.methodPlanAfter("a +"), "sum")

		assertThat(out).contains("\r\n\r\n\tprivate int sum(int a, int b) {\r\n")
		assertThat(out.replace("\r\n", "")).doesNotContain("\n")
	}

	@Test
	fun `a text block interior is emitted verbatim`() {
		val quotes = "\"\"\""
		val f =
			JavacFixture(
				"class Fixture {\n" +
					"\tString m() {\n" +
					"\t\treturn $quotes\n" +
					"\t\t\tone\n" +
					"\t\t\t  two\n" +
					"\t\t\t$quotes;\n" +
					"\t}\n" +
					"}",
			).also { fixtures += it }

		val out = f.applyMethod(f.methodPlanOver("return $quotes\n\t\t\tone\n\t\t\t  two\n\t\t\t$quotes;"), "banner")

		// The literal's own lines keep their original columns; only the statements around them move.
		assertThat(out).contains("\t\t\tone\n\t\t\t  two\n\t\t\t$quotes;")
		assertWithMessage(out).that(compiles(out)).isTrue()
	}

	@Test
	fun `a rewrite is refused when the insertion point is inside the region`() {
		val f = fixture("""	int m(int a, int b) {${'\n'}		return a + b;${'\n'}	}""")
		val candidate = f.methodPlanAfter("a +").candidates.first()

		val broken = candidate.copy(insertOffset = candidate.span.start + 1)

		assertThat(buildExtractMethodRewrites(f.text, broken, "sum")).isNull()
	}

	private fun fixture(body: String) =
		JavacFixture(
			"""
			|class Fixture {
			|$body
			|	static void use(int value) {}
			|	static void use(Object value) {}
			|}
			""".trimMargin(),
		).also { fixtures += it }
}
