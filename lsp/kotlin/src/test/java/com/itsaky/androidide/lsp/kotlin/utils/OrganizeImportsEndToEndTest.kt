package com.itsaky.androidide.lsp.kotlin.utils

import com.itsaky.androidide.lsp.kotlin.actions.OrganizeImportsAction
import com.itsaky.androidide.lsp.kotlin.fixtures.KtLspTest
import com.itsaky.androidide.models.Position
import com.itsaky.androidide.models.Range
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

	@Test
	fun `keeps constructor-only import`() {
		createSourceFile(
			"lib/Lib.kt",
			"""
			package lib
			class Widget(val n: Int)
			""".trimIndent(),
		)
		createSourceFile(
			"Main.kt",
			"""
			package p
			import lib.Widget
			fun f() { val w = Widget(1) }
			""".trimIndent(),
		)
		val mainPath = env.sourceRoots.first().resolve("Main.kt")
		val edits = OrganizeImportsAction().computeOrganizeEdit(env, mainPath)
		// Already organized -> no edit. A dropped import would produce a rewrite that removes it.
		assertTrue("constructor-only import must survive", edits.isEmpty())
	}

	@Test
	fun `keeps annotation-only import`() {
		createSourceFile(
			"lib/Lib.kt",
			"""
			package lib
			annotation class Marker
			""".trimIndent(),
		)
		createSourceFile(
			"Main.kt",
			"""
			package p
			import lib.Marker
			@Marker fun f() {}
			""".trimIndent(),
		)
		val mainPath = env.sourceRoots.first().resolve("Main.kt")
		val edits = OrganizeImportsAction().computeOrganizeEdit(env, mainPath)
		assertTrue("annotation-only import must survive", edits.isEmpty())
	}

	@Test
	fun `keeps typealias-only import used as constructor`() {
		createSourceFile(
			"lib/Lib.kt",
			"""
			package lib
			class RealList
			typealias Alias = RealList
			""".trimIndent(),
		)
		createSourceFile(
			"Main.kt",
			"""
			package p
			import lib.Alias
			fun f() { val a = Alias() }
			""".trimIndent(),
		)
		val mainPath = env.sourceRoots.first().resolve("Main.kt")
		val edits = OrganizeImportsAction().computeOrganizeEdit(env, mainPath)
		assertTrue("typealias-only import used as constructor must survive", edits.isEmpty())
	}
}
