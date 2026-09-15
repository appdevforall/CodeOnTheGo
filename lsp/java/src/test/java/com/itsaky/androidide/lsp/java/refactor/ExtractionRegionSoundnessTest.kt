package com.itsaky.androidide.lsp.java.refactor

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class ExtractionRegionSoundnessTest {
	@Test
	fun `sibling statements in one block resolve to a single statement region`() {
		val f = fixture("\tvoid m() {\n\t\tint a = 1;\n\t\tint b = 2;\n\t}")
		val regions = f.select("int a = 1;\n\t\tint b = 2;")
		assertThat(regions).hasSize(1)
		val only = regions.single()
		assertThat(only).isInstanceOf(ExtractionRegion.Statements::class.java)
		assertThat((only as ExtractionRegion.Statements).statements).hasSize(2)
		assertThat(f.spanText(only)).isEqualTo("int a = 1;\n\t\tint b = 2;")
	}

	@Test
	fun `a selection inside a switch case does not widen to the whole switch`() {
		val f =
			fixture(
				"\tvoid m(int a) {\n\t\tswitch (a) {\n\t\t\tcase 1:\n\t\t\t\tr(a);\n\t\t\t\tbreak;\n\t\t\tdefault:\n\t\t\t\tbreak;\n\t\t}\n\t}",
			)
		val regions = f.select("r(a);\n\t\t\t\tbreak;")
		assertThat(regions.filterIsInstance<ExtractionRegion.Statements>()).isEmpty()
		assertThat(regions.none { f.spanText(it).contains("switch") }).isTrue()
	}

	@Test
	fun `a selection inside an un-braced if body does not widen to the whole if`() {
		val f = fixture("\tvoid m(boolean flag) {\n\t\tif (flag) return;\n\t}")
		val regions = f.select("return;")
		assertThat(regions.filterIsInstance<ExtractionRegion.Statements>()).isEmpty()
		assertThat(regions.none { f.spanText(it).contains("if (flag)") }).isTrue()
	}

	@Test
	fun `a selection strictly inside one statement falls back to the expression path`() {
		val f = fixture("\tvoid m(int a, int b) {\n\t\tint c = a + b;\n\t}")
		val regions = f.select("a + b")
		assertThat(regions).isNotEmpty()
		assertThat(regions.all { it is ExtractionRegion.Expression }).isTrue()
		assertThat(regions.map { f.spanText(it) }).contains("a + b")
	}

	@Test
	fun `a selection spanning two blocks is declined`() {
		val f = fixture("\tvoid m(boolean flag) {\n\t\tif (flag) {\n\t\t\tg();\n\t\t}\n\t\tp();\n\t}")
		val start = f.text.indexOf("g();")
		val end = f.text.indexOf("p();") + "p();".length
		assertThat(f.regions(start, end)).isEmpty()
	}

	@Test
	fun `absorbing a trailing semicolon never reaches into the next statement`() {
		val f = fixture("\tvoid m() {\n\t\tp();;\n\t}")
		val regions = f.select("p();")
		val only = regions.single()
		assertThat(only).isInstanceOf(ExtractionRegion.Statements::class.java)
		assertWithMessage(f.spanText(only)).that(f.spanText(only)).isEqualTo("p();")
	}

	@Test
	fun `a bare assignment to a local is not an extract-method target`() {
		val f = fixture("\tvoid m(int a) {\n\t\tint total = 0;\n\t\ttotal = a + 1;\n\t}")
		val texts = f.candidateTextsAt("total = a + 1", inside = "a + 1")
		assertThat(texts).doesNotContain("total = a + 1")
		assertThat(texts).contains("a + 1")
	}

	@Test
	fun `a bare increment of a local is not an extract-method target`() {
		val f = fixture("\tvoid m(int a) {\n\t\tint i = a;\n\t\ti++;\n\t}")
		val texts = f.candidateTextsAt("i++", inside = "i+")
		assertThat(texts).doesNotContain("i++")
	}

	@Test
	fun `a bare assignment to a field stays an extract-method target`() {
		val f = fixture("\tvoid m(int a) {\n\t\tthis.fld = a + 1;\n\t}")
		val texts = f.candidateTextsAt("this.fld = a + 1", inside = "a + 1")
		assertThat(texts).contains("this.fld = a + 1")
	}

	private fun JavacFixture.regions(
		start: Int,
		end: Int,
	) = resolveExtractionRegions(task, root, text, start, end)

	private fun JavacFixture.select(substring: String): List<ExtractionRegion> {
		val start = text.indexOf(substring)
		require(start >= 0) { "the fixture contains no '$substring'" }
		return regions(start, start + substring.length)
	}

	private fun JavacFixture.spanText(region: ExtractionRegion) = text.substring(region.span.start, region.span.end)

	private fun JavacFixture.candidateTextsAt(
		statement: String,
		inside: String,
	): List<String> {
		val statementStart = text.indexOf(statement)
		require(statementStart >= 0) { "the fixture contains no '$statement'" }
		val cursor = text.indexOf(inside, statementStart) + 1
		return regions(cursor, cursor).map { spanText(it) }
	}

	private fun fixture(members: String): JavacFixture =
		JavacFixture(
			"class F {\n\tint fld;\n\tvoid r(int x) {}\n\tvoid g() {}\n\tvoid p() {}\n$members\n}",
		).also { fixtures += it }

	@After
	fun closeFixtures() {
		fixtures.forEach(JavacFixture::close)
		fixtures.clear()
	}

	private val fixtures = mutableListOf<JavacFixture>()
}
