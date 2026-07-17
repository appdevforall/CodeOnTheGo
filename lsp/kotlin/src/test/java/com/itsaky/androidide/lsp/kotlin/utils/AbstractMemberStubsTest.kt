package com.itsaky.androidide.lsp.kotlin.utils

import com.itsaky.androidide.lsp.kotlin.compiler.modules.analyzeMaybeDangling
import com.itsaky.androidide.lsp.kotlin.compiler.read
import com.itsaky.androidide.lsp.kotlin.fixtures.KtLspTest
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AbstractMemberStubsTest : KtLspTest() {
	/** Renders every unimplemented member of the class/object named [className] with a one-tab member indent. */
	private fun stubsFor(
		content: String,
		className: String,
	): List<String> {
		val ktFile = createSourceFile("Sample.kt", content)
		return env.project.read {
			analyzeMaybeDangling(ktFile) {
				val decl =
					PsiTreeUtil.collectElementsOfType(ktFile, KtClassOrObject::class.java)
						.first { it.name == className }
				val symbol = decl.symbol as KaClassSymbol
				membersToImplement(symbol).mapNotNull { renderOverrideStub(it, "\t", "\t") }
			}
		}
	}

	@Test
	fun `renders unit-returning function stub`() {
		val stubs =
			stubsFor(
				"""
				package p
				interface I { fun foo() }
				class C : I
				""".trimIndent(),
				"C",
			)
		assertEquals(1, stubs.size)
		assertEquals(
			"\toverride fun foo() {\n\t\tTODO(\"Not yet implemented\")\n\t}",
			stubs.single(),
		)
	}

	@Test
	fun `renders function stub with params and return type`() {
		val stubs =
			stubsFor(
				"""
				package p
				interface I { fun bar(x: Int, s: String): Int }
				class C : I
				""".trimIndent(),
				"C",
			)
		assertEquals(
			"\toverride fun bar(x: Int, s: String): Int {\n\t\tTODO(\"Not yet implemented\")\n\t}",
			stubs.single(),
		)
	}

	@Test
	fun `renders val property stub with a getter`() {
		val stubs =
			stubsFor(
				"""
				package p
				interface I { val p: Int }
				class C : I
				""".trimIndent(),
				"C",
			)
		assertEquals(
			"\toverride val p: Int\n\t\tget() = TODO(\"Not yet implemented\")",
			stubs.single(),
		)
	}

	@Test
	fun `renders var property stub with getter and setter`() {
		val stubs =
			stubsFor(
				"""
				package p
				interface I { var q: String }
				class C : I
				""".trimIndent(),
				"C",
			)
		assertEquals(
			"\toverride var q: String\n\t\tget() = TODO(\"Not yet implemented\")\n\t\tset(value) {}",
			stubs.single(),
		)
	}

	@Test
	fun `renders generic function stub`() {
		val stubs =
			stubsFor(
				"""
				package p
				interface I { fun <T> g(t: T): T }
				class C : I
				""".trimIndent(),
				"C",
			)
		assertEquals(
			"\toverride fun <T> g(t: T): T {\n\t\tTODO(\"Not yet implemented\")\n\t}",
			stubs.single(),
		)
	}

	@Test
	fun `renders bounded generic function stub`() {
		val stubs =
			stubsFor(
				"""
				package p
				interface I { fun <T : Number> h(t: T) }
				class C : I
				""".trimIndent(),
				"C",
			)
		assertEquals(
			"\toverride fun <T : Number> h(t: T) {\n\t\tTODO(\"Not yet implemented\")\n\t}",
			stubs.single(),
		)
	}

	@Test
	fun `renders suspend function stub`() {
		val stubs =
			stubsFor(
				"""
				package p
				interface I { suspend fun s() }
				class C : I
				""".trimIndent(),
				"C",
			)
		assertEquals(
			"\toverride suspend fun s() {\n\t\tTODO(\"Not yet implemented\")\n\t}",
			stubs.single(),
		)
	}

	@Test
	fun `collects members from multiple supertypes`() {
		val stubs =
			stubsFor(
				"""
				package p
				interface A { fun a() }
				interface B { fun b() }
				class C : A, B
				""".trimIndent(),
				"C",
			)
		assertEquals(2, stubs.size)
		assertTrue(stubs.any { it.contains("fun a()") })
		assertTrue(stubs.any { it.contains("fun b()") })
	}

	@Test
	fun `no stubs when everything is implemented`() {
		val stubs =
			stubsFor(
				"""
				package p
				interface I { fun foo() }
				class C : I {
					override fun foo() {}
				}
				""".trimIndent(),
				"C",
			)
		assertTrue("fully-implemented class needs no stubs", stubs.isEmpty())
	}

	@Test
	fun `no stubs for concrete inherited members`() {
		val stubs =
			stubsFor(
				"""
				package p
				interface I {
					fun abstractOne()
					fun defaulted() {}
				}
				class C : I
				""".trimIndent(),
				"C",
			)
		assertEquals(1, stubs.size)
		assertTrue("only the abstract member needs a stub", stubs.single().contains("fun abstractOne()"))
	}
}
