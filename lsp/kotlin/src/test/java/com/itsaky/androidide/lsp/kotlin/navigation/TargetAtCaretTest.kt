package com.itsaky.androidide.lsp.kotlin.navigation

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.lsp.kotlin.compiler.read
import com.itsaky.androidide.lsp.kotlin.fixtures.KtLspTest
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtDestructuringDeclarationEntry
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtOperationReferenceExpression
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.junit.Test

/**
 * R2's caret rules for find usages. Pure PSI, no analysis session.
 *
 * The interesting cases are the ones where this must answer *differently* from
 * [ReferenceAtCaretTest]: a caret on a declaration's own name is nothing to navigate to, but it is
 * the normal place to search for usages from.
 */
class TargetAtCaretTest : KtLspTest() {
	/** The target for a caret at `text.indexOf(marker) + delta` in a file containing [text]. */
	private fun targetAt(
		name: String,
		text: String,
		marker: String,
		delta: Int = 0,
	): CaretTarget? {
		val file = createSourceFile(name, text)
		val offset =
			text.indexOf(marker).also { check(it >= 0) { "marker '$marker' not in source" } } + delta
		return env.project.read { targetAtCaret(file, offset) }
	}

	private fun assertDeclaration(
		target: CaretTarget?,
		name: String,
	): CaretTarget.Declaration {
		assertThat(target).isInstanceOf(CaretTarget.Declaration::class.java)
		val declaration = (target as CaretTarget.Declaration)
		assertThat(declaration.declaration.name).isEqualTo(name)
		return declaration
	}

	@Test
	fun `caret on a function's own name targets that function`() {
		val target = targetAt("A.kt", "fun target() {}", "target", delta = 1)
		assertDeclaration(target, "target")
		assertThat((target as CaretTarget.Declaration).declaration).isInstanceOf(KtNamedFunction::class.java)
	}

	@Test
	fun `caret on a class's own name targets that class`() {
		val target = targetAt("B.kt", "class Widget", "Widget", delta = 2)
		assertDeclaration(target, "Widget")
		assertThat((target as CaretTarget.Declaration).declaration).isInstanceOf(KtClass::class.java)
	}

	@Test
	fun `caret on a property's own name targets that property`() {
		val target = targetAt("C.kt", "fun caller() {\n\tval count = 1\n}", "count", delta = 1)
		assertDeclaration(target, "count")
		assertThat((target as CaretTarget.Declaration).declaration).isInstanceOf(KtProperty::class.java)
	}

	@Test
	fun `caret on a parameter's own name targets that parameter`() {
		val target = targetAt("D.kt", "fun caller(value: Int) = value", "value", delta = 1)
		assertDeclaration(target, "value")
		assertThat((target as CaretTarget.Declaration).declaration).isInstanceOf(KtParameter::class.java)
	}

	/**
	 * The contrast that makes this file necessary: `referenceAtCaret` returns null here, because a
	 * declaration's own name is not something go-to-definition can navigate to.
	 */
	@Test
	fun `a caret that go-to-definition rejects still yields a target`() {
		val text = "fun target() {}"
		val file = createSourceFile("E.kt", text)
		val offset = text.indexOf("target") + 1

		env.project.read {
			assertThat(referenceAtCaret(file, offset)).isNull()
			assertThat(targetAtCaret(file, offset)).isInstanceOf(CaretTarget.Declaration::class.java)
		}
	}

	@Test
	fun `caret on a call targets the reference, not the enclosing declaration`() {
		// The nearest enclosing KtNamedDeclaration is `caller`, so this only works because the
		// declaration check requires the caret's leaf to *be* that declaration's name identifier.
		val target = targetAt("F.kt", "fun target() {}\nfun caller() { target() }", "{ target()", delta = 3)
		assertThat(target).isInstanceOf(CaretTarget.Reference::class.java)
	}

	@Test
	fun `caret one past a declaration's name targets that declaration`() {
		// The character after `target` is '(', which is navigable in its own right (the invoke
		// convention), so this asserts the declaration check runs on the primary leaf before any
		// reference interpretation of it.
		val target = targetAt("G.kt", "fun target() {}", "target", delta = 6)
		assertDeclaration(target, "target")
	}

	@Test
	fun `caret on a local declaration inside a lambda targets that declaration`() {
		// ReferenceAtCaretTest asserts this same caret navigates nowhere. Searching for usages of a
		// local function is legitimate, so it must not inherit that null.
		val target =
			targetAt(
				"H.kt",
				"fun run(block: () -> Unit) {}\nfun caller() { run { fun inner() {} } }",
				"inner",
				delta = 1,
			)
		assertDeclaration(target, "inner")
	}

	/**
	 * Q15c / R2: a destructuring entry is simultaneously a declaration and a convention reference to
	 * `componentN`. Go-to-definition reads it as the reference; find usages reads it as the
	 * declaration, so a search from here finds usages of `x` rather than of `component1`.
	 */
	@Test
	fun `caret on a destructuring entry targets the entry as a declaration`() {
		val target =
			targetAt(
				"I.kt",
				"data class P(val x: Int, val y: Int)\nfun caller(p: P) { val (x, y) = p }",
				"(x, y)",
				delta = 1,
			)
		assertDeclaration(target, "x")
		assertThat((target as CaretTarget.Declaration).declaration)
			.isInstanceOf(KtDestructuringDeclarationEntry::class.java)
	}

	@Test
	fun `caret on an operator targets the operation reference`() {
		val target =
			targetAt(
				"J.kt",
				"class P { operator fun plus(other: P): P = this }\nfun caller(a: P, b: P) { a + b }",
				"a + b",
				delta = 2,
			)
		assertThat(target).isInstanceOf(CaretTarget.Reference::class.java)
		assertThat((target as CaretTarget.Reference).element)
			.isInstanceOf(KtOperationReferenceExpression::class.java)
	}

	@Test
	fun `caret on whitespace yields no target`() {
		assertThat(targetAt("K.kt", "fun caller() {   }", "   ", delta = 1)).isNull()
	}

	@Test
	fun `caret in a comment yields no target`() {
		assertThat(targetAt("L.kt", "// target here\nfun target() {}", "target here", delta = 1)).isNull()
	}

	@Test
	fun `caret on a non-navigable keyword yields no target`() {
		assertThat(targetAt("M.kt", "fun target() {}", "fun", delta = 1)).isNull()
	}
}
