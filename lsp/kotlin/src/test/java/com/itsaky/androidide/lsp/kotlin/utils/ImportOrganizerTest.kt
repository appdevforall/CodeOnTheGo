package com.itsaky.androidide.lsp.kotlin.utils

import com.itsaky.androidide.lsp.kotlin.compiler.read
import com.itsaky.androidide.lsp.kotlin.fixtures.KtLspTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImportOrganizerTest : KtLspTest() {
	private fun organize(
		content: String,
		usage: ImportUsage,
	): String? {
		val ktFile = createSourceFile("Sample.kt", content)
		return env.project.read { organizedImportBlock(ktFile, usage) }
	}

	@Test
	fun `removes unused named import and keeps used one`() {
		val result =
			organize(
				"""
				package p
				import a.b.Used
				import a.b.Unused
				fun f(x: Used) {}
				""".trimIndent(),
				ImportUsage(usedFqNames = setOf("a.b.Used"), usedPackages = setOf("a.b")),
			)
		assertEquals("import a.b.Used", result)
	}

	@Test
	fun `sorts imports lexicographically`() {
		val result =
			organize(
				"""
				package p
				import a.b.Zebra
				import a.b.Apple
				fun f(x: Zebra, y: Apple) {}
				""".trimIndent(),
				ImportUsage(setOf("a.b.Zebra", "a.b.Apple"), setOf("a.b")),
			)
		assertEquals("import a.b.Apple${'\n'}import a.b.Zebra", result)
	}

	@Test
	fun `keeps used wildcard, removes unused wildcard`() {
		val result =
			organize(
				"""
				package p
				import used.pkg.*
				import unused.pkg.*
				fun f(x: Thing) {}
				""".trimIndent(),
				ImportUsage(usedFqNames = setOf("used.pkg.Thing"), usedPackages = setOf("used.pkg")),
			)
		assertEquals("import used.pkg.*", result)
	}

	@Test
	fun `removes default-import-redundant named import`() {
		val result =
			organize(
				"""
				package p
				import kotlin.collections.List
				import a.b.Used
				fun f(x: Used) {}
				""".trimIndent(),
				// even though List resolves, it is redundant (default star package)
				ImportUsage(setOf("a.b.Used", "kotlin.collections.List"), setOf("a.b", "kotlin.collections")),
			)
		assertEquals("import a.b.Used", result)
	}

	@Test
	fun `removes same-package redundant import`() {
		val result =
			organize(
				"""
				package p
				import p.Sibling
				import a.b.Used
				fun f(x: Used, y: Sibling) {}
				""".trimIndent(),
				ImportUsage(setOf("a.b.Used", "p.Sibling"), setOf("a.b", "p")),
			)
		assertEquals("import a.b.Used", result)
	}

	@Test
	fun `keeps aliased import from default package`() {
		val result =
			organize(
				"""
				package p
				import kotlin.collections.List as KList
				import x.y.Unused
				fun f(x: KList<String>) {}
				""".trimIndent(),
				ImportUsage(setOf("kotlin.collections.List"), setOf("kotlin.collections")),
			)
		assertEquals("import kotlin.collections.List as KList", result)
	}

	@Test
	fun `keeps import referenced only in KDoc`() {
		val result =
			organize(
				"""
				package p
				import a.b.DocOnly
				import x.y.Unused
				/** See [DocOnly] for details. */
				fun f() {}
				""".trimIndent(),
				ImportUsage(usedFqNames = emptySet(), usedPackages = emptySet()),
			)
		assertEquals("import a.b.DocOnly", result)
	}

	@Test
	fun `keeps import matching an unresolved reference`() {
		val result =
			organize(
				"""
				package p
				import a.b.Mystery
				import x.y.Unused
				fun f(m: Mystery) {}
				""".trimIndent(),
				// Mystery didn't resolve, so it's absent from usedFqNames but present as unresolved.
				ImportUsage(usedFqNames = emptySet(), usedPackages = emptySet(), unresolvedNames = setOf("Mystery")),
			)
		assertEquals("import a.b.Mystery", result)
	}

	@Test
	fun `collapses exact duplicate imports`() {
		val result =
			organize(
				"""
				package p
				import a.b.Used
				import a.b.Used
				fun f(x: Used) {}
				""".trimIndent(),
				ImportUsage(setOf("a.b.Used"), setOf("a.b")),
			)
		assertEquals("import a.b.Used", result)
	}

	@Test
	fun `returns null when already organized`() {
		val result =
			organize(
				"""
				package p
				import a.b.Apple
				import a.b.Zebra
				fun f(x: Apple, y: Zebra) {}
				""".trimIndent(),
				ImportUsage(setOf("a.b.Apple", "a.b.Zebra"), setOf("a.b")),
			)
		assertNull(result)
	}

	@Test
	fun `no imports returns null`() {
		val result =
			organize(
				"""
				package p
				fun f() {}
				""".trimIndent(),
				ImportUsage(emptySet(), emptySet()),
			)
		assertNull(result)
	}
}
