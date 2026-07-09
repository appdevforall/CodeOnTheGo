package com.itsaky.androidide.lsp.kotlin.utils

import com.itsaky.androidide.lsp.kotlin.actions.OrganizeImportsAction
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
		createSourceFile(
			"Main.kt",
			"""
			package p
			import lib.Used
			import lib.Unused
			fun f(x: Used) {}
			""".trimIndent(),
		)
		val mainPath = env.sourceRoots.first().resolve("Main.kt")

		// Drive the action's real plumbing: fetch-before-read ordering + full guard chain.
		val edits = OrganizeImportsAction().computeOrganizeEdit(env, mainPath)

		assertEquals(1, edits.size)
		assertEquals("import lib.Used", edits.single().newText)
	}
}
