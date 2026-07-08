package com.itsaky.androidide.lsp.kotlin.signaturehelp

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.lsp.kotlin.fixtures.KtLspTest
import com.itsaky.androidide.lsp.models.SignatureHelp
import org.junit.Test

class KotlinSignatureHelpTest : KtLspTest() {
	private fun helpAt(
		name: String,
		text: String,
		marker: String,
		delta: Int = 0,
	): SignatureHelp {
		val file = createSourceFile(name, text)
		val offset =
			text.indexOf(marker).let {
				check(it >= 0)
				it
			} + delta
		return analyze(file) {
			val call = findEnclosingCall(file, offset) ?: return@analyze SignatureHelp.empty()
			buildSignatureHelp(call, offset)
		}
	}

	@Test
	fun `single function returns one signature with valid indices`() {
		val help = helpAt("A.kt", "fun f(a: Int) {}\nfun g() { f(1) }", "f(1)", delta = 2)
		assertThat(help.signatures).hasSize(1)
		assertThat(help.signatures[0].label).isEqualTo("f(a: Int)")
		assertThat(help.activeSignature).isEqualTo(0)
		assertThat(help.activeParameter).isEqualTo(0)
	}

	@Test
	fun `overloads all appear and the matching one is active`() {
		val text =
			"""
			fun f(a: Int) {}
			fun f(a: Int, b: String) {}
			fun g() { f(1, "x") }
			""".trimIndent()
		val help = helpAt("B.kt", text, "f(1, ", delta = 2)
		assertThat(help.signatures.map { it.label })
			.containsExactly("f(a: Int)", "f(a: Int, b: String)")
		// active overload is the two-arg one
		assertThat(help.signatures[help.activeSignature].label).isEqualTo("f(a: Int, b: String)")
	}

	@Test
	fun `constructor call produces a signature labelled with the class name`() {
		val text = "class Point(val x: Int, val y: Int)\nfun g() { Point(1, 2) }"
		val help = helpAt("C.kt", text, "Point(1", delta = 6)
		assertThat(help.signatures).isNotEmpty()
		assertThat(help.signatures[help.activeSignature].label).isEqualTo("Point(x: Int, y: Int)")
	}

	@Test
	fun `unresolved call returns empty`() {
		val help = helpAt("D.kt", "fun g() { doesNotExist(1) }", "doesNotExist(", delta = 13)
		assertThat(help.signatures).isEmpty()
		assertThat(help).isEqualTo(SignatureHelp.empty())
	}

	@Test
	fun `cursor in trailing lambda body yields empty via finder`() {
		val text = "fun run(block: () -> Unit) {}\nfun g() { run { p() } }"
		val help = helpAt("E.kt", text, "p()", delta = 1)
		assertThat(help).isEqualTo(SignatureHelp.empty())
	}

	@Test
	fun `named argument remaps active parameter via buildSignatureHelp`() {
		val help = helpAt("F.kt", "fun f(a: Int, b: Int) {}\nfun g() { f(b = 1, a = 5) }", "a = 5")
		// 'a' is declared parameter 0 though written second
		assertThat(help.activeParameter).isEqualTo(0)
		assertThat(help.signatures).isNotEmpty()
	}
}
