package com.itsaky.androidide.lsp.kotlin.utils

import com.itsaky.androidide.lsp.kotlin.actions.OrganizeImportsAction
import com.itsaky.androidide.lsp.kotlin.fixtures.KtLspTest
import com.itsaky.androidide.models.Position
import com.itsaky.androidide.models.Range
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

		// The import list in the fixture spans exactly line 1 col 0 ("import lib.Used") through
		// line 2 col 17 (end of "import lib.Unused"); line 0 is "package p". A wrong/off-by-one
		// range here would silently corrupt the file when the client applies this edit.
		assertEquals(
			Range(Position(1, 0), Position(2, 17)),
			edits.single().range,
		)
	}
}
