package com.itsaky.androidide.lsp.java.refactor

import com.google.common.truth.Truth.assertThat
import openjdk.source.util.Trees
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Region resolution (R2): which selection becomes which kind of region, and the two positions extract
 * method accepts that extract variable must refuse.
 *
 * Parse-level only -- no capture, output or exit analysis reaches this layer.
 */
@RunWith(JUnit4::class)
class ExtractMethodRegionTest {
	private val fixtures = mutableListOf<JavacFixture>()

	@After
	fun tearDown() = fixtures.forEach(JavacFixture::close)

	@Test
	fun `a bare cursor resolves to nested expressions innermost first`() {
		val f = fixture("""	int m(int a, int b) {${'\n'}		return compute(a + b);${'\n'}	}${'\n'}	int compute(int v) { return v; }""")

		val regions = f.regionsAfter("a +")

		assertThat(regions.map { it.text(f) }).containsExactly("a + b", "compute(a + b)").inOrder()
	}

	@Test
	fun `a selection over two whole statements is a statement range`() {
		val f = fixture("""	void m() {${'\n'}		int a = 1;${'\n'}		use(a);${'\n'}	}""")

		val regions = f.regionsOver("int a = 1;${'\n'}		use(a);")

		val only = regions.single() as ExtractionRegion.Statements
		assertThat(only.statements).hasSize(2)
		assertThat(only.text(f)).isEqualTo("int a = 1;\n\t\tuse(a);")
	}

	@Test
	fun `a ragged selection snaps outward to whole statements`() {
		val f = fixture("""	void m() {${'\n'}		int a = 1;${'\n'}		use(a);${'\n'}	}""")

		// Starts inside `int` and stops inside `use(a)`, as a touch drag would.
		val regions = f.regionsOver("nt a = 1;${'\n'}		use(")

		val only = regions.single() as ExtractionRegion.Statements
		assertThat(only.text(f)).isEqualTo("int a = 1;\n\t\tuse(a);")
	}

	@Test
	fun `a selection spanning two blocks resolves to nothing`() {
		val f =
			fixture(
				"""	void m(int x) {${'\n'}		if (x > 0) {${'\n'}			use(x);${'\n'}		}${'\n'}		use(1);${'\n'}	}""",
			)

		// From inside the `if` body out to the statement after the `if`: two different blocks.
		val regions = f.regionsOver("use(x);${'\n'}		}${'\n'}		use(1);")

		assertThat(regions).isEmpty()
	}

	@Test
	fun `a selection inside one statement prefers the expression it points at`() {
		val f = fixture("""	void m(int a, int b) {${'\n'}		use(a + b);${'\n'}	}""")

		val regions = f.regionsOver("a + b")

		assertThat(regions.first()).isInstanceOf(ExtractionRegion.Expression::class.java)
		assertThat(regions.first().text(f)).isEqualTo("a + b")
	}

	// --- the two positions extract variable refuses and extract method does not (R2) ---

	@Test
	fun `a cursor inside an expression statement offers the call`() {
		val f = fixture("""	void m(java.util.List<String> items) {${'\n'}		items.add("x");${'\n'}	}""")

		// Extract variable refuses it: replacing the expression with a name leaves a bare `v;`.
		assertThat(f.planAfter("items.add").candidates.map { it.label }).doesNotContain("items.add(\"x\")")
		// Extract method replaces it with a call, which is a statement.
		assertThat(f.regionsAfter("items.add").map { it.text(f) }).contains("items.add(\"x\")")
	}

	@Test
	fun `a cursor on a short-circuit right operand offers a candidate`() {
		val f = fixture("""	boolean m(String s) {${'\n'}		return s != null && s.length() > 0;${'\n'}	}""")

		// Hoisting it out of the guard would evaluate it unguarded, so extract variable declines.
		assertThat(f.planAfter("s.length()").candidates).isEmpty()
		// Substituting a call in place changes nothing about when it runs.
		assertThat(f.regionsAfter("s.length()")).isNotEmpty()
	}

	@Test
	fun `a cursor on a loop condition offers a candidate`() {
		val f =
			fixture(
				"""	void m(java.util.Iterator<String> it) {${'\n'}		while (it.hasNext()) {${'\n'}			use(it.next());${'\n'}		}${'\n'}	}""",
			)

		assertThat(f.planAfter("it.hasNext()").candidates).isEmpty()
		assertThat(f.regionsAfter("it.hasNext()").map { it.text(f) }).contains("it.hasNext()")
	}

	private fun ExtractionRegion.text(f: JavacFixture) = f.text.substring(span.start, span.end)

	private fun JavacFixture.regionsAfter(prefix: String): List<ExtractionRegion> {
		val cursor = cursorAfter(prefix)
		return regionsBetween(cursor, cursor)
	}

	private fun JavacFixture.regionsOver(selection: String): List<ExtractionRegion> {
		val start = text.indexOf(selection)
		require(start >= 0) { "the fixture contains no '$selection'" }
		return regionsBetween(start, start + selection.length)
	}

	private fun JavacFixture.regionsBetween(
		start: Int,
		end: Int,
	): List<ExtractionRegion> = resolveExtractionRegions(task, root, Trees.instance(task).sourcePositions, text, start, end)

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
