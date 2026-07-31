package com.itsaky.androidide.lsp.kotlin.navigation

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.lsp.kotlin.compiler.read
import com.itsaky.androidide.lsp.kotlin.fixtures.KtLspTest
import org.jetbrains.kotlin.psi.KtArrayAccessExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDestructuringDeclarationEntry
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtOperationReferenceExpression
import org.jetbrains.kotlin.psi.KtPropertyDelegate
import org.junit.Test

class ReferenceAtCaretTest : KtLspTest() {
	/** Resolves the caret at `text.indexOf(marker) + delta` in a file containing [text]. */
	private fun refAt(
		name: String,
		text: String,
		marker: String,
		delta: Int = 0,
	): KtElement? {
		val file = createSourceFile(name, text)
		val offset =
			text.indexOf(marker).also { check(it >= 0) { "marker '$marker' not in source" } } + delta
		return env.project.read { referenceAtCaret(file, offset) }
	}

	@Test
	fun `caret inside an identifier finds its name reference`() {
		// "target()" also matches the declaration; anchor on "{ target()" to land in the call.
		val ref = refAt("A.kt", "fun target() {}\nfun caller() { target() }", "{ target()", delta = 3)
		assertThat(ref).isInstanceOf(KtNameReferenceExpression::class.java)
		assertThat(ref!!.text).isEqualTo("target")
	}

	@Test
	fun `caret immediately after an identifier retries one character back`() {
		// The character after `value` is whitespace, which names nothing, so the offset-1 retry is
		// what finds the reference. This is the case R2's retry exists for.
		val text = "fun caller(value: Int) = value + 1"
		val ref = refAt("B.kt", text, "value + 1", delta = 5)
		assertThat(ref).isInstanceOf(KtNameReferenceExpression::class.java)
		assertThat(ref!!.text).isEqualTo("value")
	}

	@Test
	fun `caret on a call's paren finds the call expression`() {
		// LPAR is navigable for the invoke convention, so the primary lookup succeeds here and the
		// offset-1 retry never fires. That is deliberate and consistent with `caret on an index
		// bracket`: a caret on a convention token resolves the convention host, coarser than the
		// identifier before it. R2's "one past an identifier behaves like inside it" guarantee still
		// holds end to end, because resolving a KtCallExpression lands on the called function - that
		// is asserted in GoToDefinitionTest, not here, since it needs an analysis session.
		val text = "fun target() {}\nfun caller() { target() }"
		val ref = refAt("B2.kt", text, "{ target()", delta = 8)
		assertThat(ref).isInstanceOf(KtCallExpression::class.java)
	}

	@Test
	fun `caret on whitespace finds nothing`() {
		assertThat(refAt("C.kt", "fun caller() {   }", "   ", delta = 1)).isNull()
	}

	@Test
	fun `caret in a comment finds nothing`() {
		assertThat(refAt("D.kt", "// target here\nfun target() {}", "target here", delta = 1)).isNull()
	}

	@Test
	fun `caret on a non-navigable keyword finds nothing`() {
		assertThat(refAt("E.kt", "fun target() {}", "fun", delta = 1)).isNull()
	}

	@Test
	fun `caret on a declaration's own name finds nothing`() {
		assertThat(refAt("F.kt", "fun target() {}", "target", delta = 1)).isNull()
	}

	@Test
	fun `caret on a local declaration's name does not climb into the enclosing call`() {
		// Without the climb cap this would reach the enclosing `run(...)` call and navigate there.
		val ref =
			refAt(
				"G.kt",
				"fun run(block: () -> Unit) {}\nfun caller() { run { fun inner() {} } }",
				"inner",
				delta = 1,
			)
		assertThat(ref).isNull()
	}

	@Test
	fun `caret on an operator finds the operation reference`() {
		val ref =
			refAt(
				"H.kt",
				"class P { operator fun plus(other: P): P = this }\nfun caller(a: P, b: P) { a + b }",
				"a + b",
				delta = 2,
			)
		assertThat(ref).isInstanceOf(KtOperationReferenceExpression::class.java)
	}

	@Test
	fun `caret on an index bracket finds the array access`() {
		val ref = refAt("I.kt", "fun caller(list: List<Int>) { list[0] }", "[0]", delta = 0)
		assertThat(ref).isInstanceOf(KtArrayAccessExpression::class.java)
	}

	@Test
	fun `caret on by finds the property delegate`() {
		val ref = refAt("J.kt", "val value: Int by lazy { 1 }", "by", delta = 1)
		assertThat(ref).isInstanceOf(KtPropertyDelegate::class.java)
	}

	@Test
	fun `caret on for-loop in finds the for expression`() {
		val ref = refAt("K.kt", "fun caller(items: List<Int>) { for (i in items) {} }", "in items", delta = 1)
		assertThat(ref).isInstanceOf(KtForExpression::class.java)
	}

	@Test
	fun `caret on a destructuring entry finds the entry`() {
		val ref =
			refAt(
				"L.kt",
				"data class P(val x: Int, val y: Int)\nfun caller(p: P) { val (x, y) = p }",
				"(x, y)",
				delta = 1,
			)
		assertThat(ref).isInstanceOf(KtDestructuringDeclarationEntry::class.java)
	}
}
