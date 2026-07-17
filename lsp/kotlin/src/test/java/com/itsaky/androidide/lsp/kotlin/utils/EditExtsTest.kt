package com.itsaky.androidide.lsp.kotlin.utils

import com.itsaky.androidide.lsp.kotlin.compiler.read
import com.itsaky.androidide.lsp.kotlin.fixtures.KtLspTest
import com.itsaky.androidide.lsp.models.TextEdit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for [insertImport]: the sorted-insertion + dedup logic the Add-import action relies on. */
class EditExtsTest : KtLspTest() {

	private val nl = System.lineSeparator()

	private fun insert(
		source: String,
		fqn: String,
	): List<TextEdit> {
		val ktFile = createSourceFile("Main.kt", source)
		return env.project.read { insertImport(ktFile, fqn) }
	}

	@Test
	fun `inserts before the first import that sorts after it`() {
		val edit =
			insert(
				"""
				package p
				import a.A
				import c.C
				""".trimIndent(),
				"b.B",
			).single()
		// Pure insertion at the start of `import c.C` (line 2, col 0).
		assertEquals(edit.range.start, edit.range.end)
		assertEquals(2, edit.range.start.line)
		assertEquals(0, edit.range.start.column)
		assertEquals("import b.B$nl", edit.newText)
	}

	@Test
	fun `inserts before the very first import`() {
		val edit =
			insert(
				"""
				package p
				import b.B
				""".trimIndent(),
				"a.A",
			).single()
		assertEquals(1, edit.range.start.line)
		assertEquals(0, edit.range.start.column)
		assertEquals("import a.A$nl", edit.newText)
	}

	@Test
	fun `appends after the last import when it sorts last`() {
		val edit =
			insert(
				"""
				package p
				import a.A
				import b.B
				""".trimIndent(),
				"z.Z",
			).single()
		// Pure insertion at the end of `import b.B` (line 2, col 10).
		assertEquals(edit.range.start, edit.range.end)
		assertEquals(2, edit.range.start.line)
		assertEquals(10, edit.range.start.column)
		assertEquals("${nl}import z.Z", edit.newText)
	}

	@Test
	fun `skips an exact duplicate import`() {
		assertTrue(
			insert(
				"""
				package p
				import a.A
				""".trimIndent(),
				"a.A",
			).isEmpty(),
		)
	}

	@Test
	fun `inserts after the package statement when there are no imports`() {
		val edit =
			insert(
				"""
				package p

				class X
				""".trimIndent(),
				"a.A",
			).single()
		assertEquals(0, edit.range.start.line)
		assertEquals(9, edit.range.start.column)
		assertEquals("${nl}import a.A", edit.newText)
	}
}
