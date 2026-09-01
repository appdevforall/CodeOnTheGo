package com.itsaky.androidide.lsp.kotlin.actions

import com.itsaky.androidide.lsp.kotlin.compiler.index.findSymbolBySimpleName
import com.itsaky.androidide.lsp.kotlin.fixtures.KtLspTest
import kotlinx.coroutines.runBlocking
import org.appdevforall.codeonthego.indexing.jvm.JvmClassInfo
import org.appdevforall.codeonthego.indexing.jvm.JvmFunctionInfo
import org.appdevforall.codeonthego.indexing.jvm.JvmSourceLanguage
import org.appdevforall.codeonthego.indexing.jvm.JvmSymbol
import org.appdevforall.codeonthego.indexing.jvm.JvmSymbolKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AddImportActionTest : KtLspTest() {
	private val mainPath get() = env.sourceRoots.first().resolve("Main.kt")

	private fun classSymbol(
		pkg: String,
		shortName: String,
	) = symbol(pkg, shortName, JvmSymbolKind.CLASS, JvmClassInfo())

	private fun funSymbol(
		pkg: String,
		shortName: String,
	) = symbol(pkg, shortName, JvmSymbolKind.FUNCTION, JvmFunctionInfo())

	private fun symbol(
		pkg: String,
		shortName: String,
		kind: JvmSymbolKind,
		data: org.appdevforall.codeonthego.indexing.jvm.JvmSymbolInfo,
	): JvmSymbol {
		val internalName = "${pkg.replace('.', '/')}/$shortName"
		return JvmSymbol(
			key = "$internalName#${kind.name}",
			sourceId = "test",
			name = internalName,
			shortName = shortName,
			packageName = pkg,
			kind = kind,
			language = JvmSourceLanguage.KOTLIN,
			data = data,
		)
	}

	private fun index(vararg symbols: JvmSymbol) = runBlocking { symbols.forEach { env.ktSymbolIndex.sourceIndex.insert(it) } }

	@Test
	fun `resolves a single classifier candidate by simple name`() {
		index(classSymbol("lib", "Foo"))
		createSourceFile("Main.kt", "package p\nimport lib.Bar\nfun f(x: Foo) {}")

		val candidates = AddImportAction().computeImportCandidates(env, mainPath, "Foo")

		assertEquals(setOf("lib.Foo"), candidates.keys)
		val edit = candidates.getValue("lib.Foo").single()
		assertEquals("import lib.Foo", edit.newText.trim())
	}

	@Test
	fun `offers every matching classifier for a multi-candidate reference`() {
		index(classSymbol("a", "Foo"), classSymbol("b", "Foo"))
		createSourceFile("Main.kt", "package p\nfun f(x: Foo) {}")

		val candidates = AddImportAction().computeImportCandidates(env, mainPath, "Foo")

		assertEquals(setOf("a.Foo", "b.Foo"), candidates.keys)
		candidates.values.forEach { assertEquals(1, it.size) }
	}

	@Test
	fun `filters out non-classifier symbols`() {
		index(classSymbol("a", "Foo"), funSymbol("b", "Foo"))
		createSourceFile("Main.kt", "package p\nfun f(x: Foo) {}")

		val candidates = AddImportAction().computeImportCandidates(env, mainPath, "Foo")

		assertEquals(setOf("a.Foo"), candidates.keys)
	}

	@Test
	fun `returns no candidates for an unknown reference`() {
		createSourceFile("Main.kt", "package p\nfun f() {}")

		assertTrue(AddImportAction().computeImportCandidates(env, mainPath, "Nope").isEmpty())
	}

	/**
	 * Regression guard: `findSymbolBySimpleName` is called with `limit = 0` (unbounded). A plain
	 * `take(0)` would return nothing, silently disabling the whole Add-import action.
	 */
	@Test
	fun `findSymbolBySimpleName treats limit 0 as unbounded and a positive limit as a cap`() {
		index(classSymbol("a", "Foo"), classSymbol("b", "Foo"), classSymbol("c", "Foo"))

		val unbounded = env.ktSymbolIndex.findSymbolBySimpleName("Foo", limit = 0).toList()
		assertEquals(setOf("a.Foo", "b.Foo", "c.Foo"), unbounded.map { it.fqName }.toSet())

		assertEquals(
			2,
			env.ktSymbolIndex
				.findSymbolBySimpleName("Foo", limit = 2)
				.toList()
				.size,
		)
	}
}
