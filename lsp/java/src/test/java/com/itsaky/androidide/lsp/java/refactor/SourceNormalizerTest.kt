package com.itsaky.androidide.lsp.java.refactor

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.lsp.refactor.detectIndentUnit
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** The occurrence matcher's text half, with no compiler involved. */
@RunWith(JUnit4::class)
class SourceNormalizerTest {
	@Test
	fun `whitespace runs collapse to one space`() {
		assertThat(normalizeSource("a   +\n\tb")).isEqualTo("a+b")
	}

	@Test
	fun `leading and trailing whitespace is dropped`() {
		assertThat(normalizeSource("  a + b  ")).isEqualTo("a+b")
	}

	@Test
	fun `line comments are stripped`() {
		assertThat(normalizeSource("a + // why\nb")).isEqualTo("a+b")
	}

	@Test
	fun `block comments are stripped`() {
		assertThat(normalizeSource("a /* note */ + b")).isEqualTo("a+b")
	}

	@Test
	fun `whitespace inside a string literal is preserved`() {
		assertThat(normalizeSource("f(\"a   b\")")).isEqualTo("f(\"a   b\")")
	}

	@Test
	fun `a comment marker inside a string literal is preserved`() {
		assertThat(normalizeSource("f(\"http://x\")")).isEqualTo("f(\"http://x\")")
	}

	@Test
	fun `an escaped quote does not end a string literal`() {
		assertThat(normalizeSource("f(\"a\\\"  b\")")).isEqualTo("f(\"a\\\"  b\")")
	}

	@Test
	fun `a char literal holding a quote is preserved`() {
		assertThat(normalizeSource("c == '\"'  ")).isEqualTo("c=='\"'")
	}

	@Test
	fun `an escaped backslash before a quote ends the literal`() {
		assertThat(normalizeSource("f(\"a\\\\\")  ")).isEqualTo("f(\"a\\\\\")")
	}

	@Test
	fun `an unterminated literal consumes to the end rather than looping`() {
		assertThat(normalizeSource("f(\"abc")).isEqualTo("f(\"abc")
	}

	@Test
	fun `two spellings of the same expression normalize equal`() {
		assertThat(normalizeSource("items.size()  +  1"))
			.isEqualTo(normalizeSource("items\n\t.size() /* n */ + 1"))
	}

	@Test
	fun `a text block keeps its significant whitespace`() {
		val block = "f(\"\"\"\n  a   b\n\"\"\")"
		assertThat(normalizeSource(block)).isEqualTo(block)
	}

	@Test
	fun `two text blocks differing only in whitespace do not normalize equal`() {
		// If they collapsed, the occurrence search would replace a site holding a different value.
		val a = normalizeSource("f(\"\"\"\n  a\n\"\"\")")
		val b = normalizeSource("f(\"\"\"\n    a\n\"\"\")")
		assertThat(a).isNotEqualTo(b)
	}

	@Test
	fun `an unterminated text block consumes to the end rather than looping`() {
		assertThat(normalizeSource("f(\"\"\"abc")).isEqualTo("f(\"\"\"abc")
	}

	@Test
	fun `a javadoc continuation line is not treated as the indent unit`() {
		val spaceIndented =
			"""
			|package qa;
			|
			|/**
			| * Doc.
			| */
			|class Qa {
			|    void f() {
			|    }
			|}
			""".trimMargin()
		// The ` * ` and ` */` lines are runs of exactly one space; before this was guarded they won the
		// minimum on virtually every real Java file.
		assertThat(detectIndentUnit(spaceIndented)).isEqualTo("    ")
	}

	@Test
	fun `a tab-indented file still reports a tab`() {
		assertThat(detectIndentUnit("class Qa {\n\tvoid f() {\n\t}\n}")).isEqualTo("\t")
	}

	@Test
	fun `a file with no indentation at all falls back to a tab`() {
		assertThat(detectIndentUnit("class Qa {}")).isEqualTo("\t")
	}

	@Test
	fun `spacing collapses around every operator, not just the dot`() {
		assertThat(normalizeSource("a+1")).isEqualTo(normalizeSource("a + 1"))
		assertThat(normalizeSource("m(x,y)")).isEqualTo(normalizeSource("m(x, y)"))
	}

	@Test
	fun `two combinable characters are kept apart`() {
		// Closing `a - -b` up to `a--b` would make it a different expression, and equal to `a-- b`.
		assertThat(normalizeSource("a - -b")).isNotEqualTo(normalizeSource("a-- b"))
	}
}
