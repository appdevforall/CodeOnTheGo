package com.itsaky.androidide.lsp.java.refactor

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** The occurrence matcher's text half, with no compiler involved. */
@RunWith(JUnit4::class)
class SourceNormalizerTest {
	@Test
	fun `whitespace runs collapse to one space`() {
		assertThat(normalizeSource("a   +\n\tb")).isEqualTo("a + b")
	}

	@Test
	fun `leading and trailing whitespace is dropped`() {
		assertThat(normalizeSource("  a + b  ")).isEqualTo("a + b")
	}

	@Test
	fun `line comments are stripped`() {
		assertThat(normalizeSource("a + // why\nb")).isEqualTo("a + b")
	}

	@Test
	fun `block comments are stripped`() {
		assertThat(normalizeSource("a /* note */ + b")).isEqualTo("a + b")
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
		assertThat(normalizeSource("c == '\"'  ")).isEqualTo("c == '\"'")
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
}
