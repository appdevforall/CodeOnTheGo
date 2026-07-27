package com.itsaky.androidide.lsp.kotlin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins each Kotlin code action to its tooltip tag. Tooltip content is authored per tag and looked
 * up by the literal string, so a wrong tag fails silently at runtime: the action either shows
 * another action's tooltip or none at all (ADFA-4867). Expected values are spelled out rather than
 * read from [com.itsaky.androidide.idetooltips.TooltipTag] so that editing a constant fails here.
 */
class KotlinCodeActionTooltipTagTest {
	private val actualTags
		get() = KotlinCodeActionsMenu.actions.associate { it.id to it.tooltipTag }

	@Test
	fun `every kotlin code action maps to its own tooltip tag`() {
		val expected =
			mapOf(
				"ide.editor.lsp.kt.commentLine" to "editor.codeactions.kotlin.comment",
				"ide.editor.lsp.kt.uncommentLine" to "editor.codeactions.kotlin.uncomment",
				"ide.editor.lsp.kt.diagnostics.addImport" to "editor.codeactions.kotlin.addimport",
				"ide.editor.lsp.kt.organizeImports" to "editor.codeactions.kotlin.organizeimports",
				"ide.editor.lsp.kt.diagnostics.nullSafety" to "editor.codeactions.kotlin.nullsafetyfix",
				"ide.editor.lsp.kt.implementMembers" to "editor.codeactions.kotlin.overridesuper",
			)
		assertEquals(expected, actualTags)
	}

	/** Guards the specific regression: a Kotlin action reusing a Java tag, or carrying none. */
	@Test
	fun `no kotlin code action borrows a java tooltip tag`() {
		actualTags.forEach { (id, tag) ->
			assertTrue(
				"$id has no tooltip tag",
				tag.isNotEmpty(),
			)
			assertTrue(
				"$id uses non-Kotlin tooltip tag '$tag'",
				tag.startsWith("editor.codeactions.kotlin."),
			)
		}
	}
}
