package com.itsaky.androidide.lsp.kotlin.utils

import com.itsaky.androidide.lsp.kotlin.compiler.modules.analyzeMaybeDangling
import com.itsaky.androidide.lsp.kotlin.compiler.read
import com.itsaky.androidide.lsp.kotlin.fixtures.KtLspTest
import org.junit.Assert.assertEquals
import org.junit.Test

class OrganizeImportsEndToEndTest : KtLspTest() {

	@Test
	fun `removes unused import end to end`() {
		createSourceFile(
			"lib/Lib.kt",
			"""
			package lib
			class Used
			class Unused
			""".trimIndent(),
		)
		val ktFile = createSourceFile(
			"Main.kt",
			"""
			package p
			import lib.Used
			import lib.Unused
			fun f(x: Used) {}
			""".trimIndent(),
		)

		val block = env.project.read {
			val usage = analyzeMaybeDangling(ktFile) { collectImportUsage(ktFile) }
			organizedImportBlock(ktFile, usage)
		}
		assertEquals("import lib.Used", block)
	}
}
