package com.itsaky.androidide.lsp.kotlin.utils

import com.itsaky.androidide.lsp.kotlin.compiler.modules.analyzeMaybeDangling
import com.itsaky.androidide.lsp.kotlin.compiler.read
import com.itsaky.androidide.lsp.kotlin.fixtures.KtLspTest
import org.jetbrains.kotlin.psi.KtFile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportUsageCollectorTest : KtLspTest() {

	private fun usageOf(ktFile: KtFile): ImportUsage =
		env.project.read { analyzeMaybeDangling(ktFile) { collectImportUsage(ktFile) } }

	@Test
	fun `type reference is recorded as used`() {
		val ktFile = createSourceFile(
			"UseType.kt",
			"""
			package p
			fun f(): java.io.File? = null
			fun g() { val x: java.io.File = java.io.File("a") }
			""".trimIndent(),
		)
		val usage = usageOf(ktFile)
		assertTrue("java.io.File" in usage.usedFqNames)
		assertTrue("java.io" in usage.usedPackages)
	}

	@Test
	fun `top-level function call is recorded as used`() {
		createSourceFile(
			"lib/Lib.kt",
			"""
			package lib
			fun topLevelHelper() {}
			""".trimIndent(),
		)
		val ktFile = createSourceFile(
			"UseFn.kt",
			"""
			package p
			import lib.topLevelHelper
			fun f() { topLevelHelper() }
			""".trimIndent(),
		)
		val usage = usageOf(ktFile)
		assertTrue("lib.topLevelHelper" in usage.usedFqNames)
	}

	@Test
	fun `operator function used via symbol is recorded`() {
		createSourceFile(
			"lib/Ops.kt",
			"""
			package lib
			class Money
			operator fun Money.plus(other: Money): Money = this
			""".trimIndent(),
		)
		val ktFile = createSourceFile(
			"UseOp.kt",
			"""
			package p
			import lib.Money
			import lib.plus
			fun f(a: Money, b: Money) { val c = a + b }
			""".trimIndent(),
		)
		val usage = usageOf(ktFile)
		assertTrue("lib.plus" in usage.usedFqNames)
	}

	@Test
	fun `unreferenced import is absent from usage`() {
		createSourceFile(
			"lib/Extra.kt",
			"""
			package lib
			class Extra
			""".trimIndent(),
		)
		val ktFile = createSourceFile(
			"NoUse.kt",
			"""
			package p
			import lib.Extra
			fun f() {}
			""".trimIndent(),
		)
		val usage = usageOf(ktFile)
		assertFalse("lib.Extra" in usage.usedFqNames)
	}
}
