package com.itsaky.androidide.lsp.kotlin.signaturehelp

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.lsp.kotlin.compiler.read
import com.itsaky.androidide.lsp.kotlin.fixtures.KtLspTest
import org.junit.Test

class CallAtCursorFinderTest : KtLspTest() {
	private fun offsetOf(
		text: String,
		marker: String,
	): Int {
		val i = text.indexOf(marker)
		check(i >= 0) { "marker '$marker' not found" }
		return i
	}

	@Test
	fun `finds call when cursor is inside the argument parentheses`() {
		val text = "fun f(a: Int) {}\nfun g() { f(1) }"
		val file = env.project.read { env.parser.createFile("A.kt", text) }
		val offset = offsetOf(text, "f(1)") + 2 // just after '('
		val call = env.project.read { findEnclosingCall(file, offset) }
		assertThat(call).isNotNull()
		assertThat(call!!.calleeExpression?.text).isEqualTo("f")
	}

	@Test
	fun `returns null when cursor is inside a trailing lambda body`() {
		val text = "fun run(block: () -> Unit) {}\nfun g() { run { println() } }"
		val file = env.project.read { env.parser.createFile("B.kt", text) }
		val offset = offsetOf(text, "println") + 1
		val call = env.project.read { findEnclosingCall(file, offset) }
		assertThat(call).isNull()
	}

	@Test
	fun `returns the innermost call for nested calls`() {
		val text = "fun inner(x: Int) = x\nfun outer(y: Int) = y\nfun g() { outer(inner(2)) }"
		val file = env.project.read { env.parser.createFile("C.kt", text) }
		val offset = offsetOf(text, "inner(2)") + 6 // inside inner's parens
		val call = env.project.read { findEnclosingCall(file, offset) }
		assertThat(call!!.calleeExpression?.text).isEqualTo("inner")
	}

	@Test
	fun `finds call for an unclosed argument list`() {
		val text = "fun f(a: Int) {}\nfun g() { f( }"
		val file = env.project.read { env.parser.createFile("D.kt", text) }
		val offset = offsetOf(text, "f( ") + 2 // after '('
		val call = env.project.read { findEnclosingCall(file, offset) }
		assertThat(call?.calleeExpression?.text).isEqualTo("f")
	}
}
