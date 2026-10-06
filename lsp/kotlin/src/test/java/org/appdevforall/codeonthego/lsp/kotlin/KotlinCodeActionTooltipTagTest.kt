package org.appdevforall.codeonthego.lsp.kotlin

import org.appdevforall.codeonthego.idetooltips.TooltipTag
import org.appdevforall.codeonthego.lsp.actions.CommentLineAction
import org.appdevforall.codeonthego.lsp.actions.SurroundWithTryCatchAction
import org.appdevforall.codeonthego.lsp.actions.UncommentLineAction
import org.appdevforall.codeonthego.lsp.kotlin.KotlinCodeActionsMenu.KT_LANG
import org.appdevforall.codeonthego.lsp.kotlin.actions.AddImportAction
import org.appdevforall.codeonthego.lsp.kotlin.actions.ExtractMethodAction
import org.appdevforall.codeonthego.lsp.kotlin.actions.ExtractVariableAction
import org.appdevforall.codeonthego.lsp.kotlin.actions.FindReferencesAction
import org.appdevforall.codeonthego.lsp.kotlin.actions.GoToDefinitionAction
import org.appdevforall.codeonthego.lsp.kotlin.actions.ImplementMembersAction
import org.appdevforall.codeonthego.lsp.kotlin.actions.InlineVariableAction
import org.appdevforall.codeonthego.lsp.kotlin.actions.NullSafetyAction
import org.appdevforall.codeonthego.lsp.kotlin.actions.OrganizeImportsAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins each Kotlin code action to its tooltip tag. Tooltip content is authored per tag and looked up
 * by that tag, so a wrong tag fails silently at runtime: the action either shows another action's
 * tooltip or none at all (ADFA-4867).
 *
 * This catches an action pointing at the wrong constant, or a newly registered action carrying no
 * tag. It cannot catch a tag's *value* being edited, since both sides read the same constant -- the
 * prefix assertion below is the backstop for a Kotlin action drifting onto a Java tag.
 */
class KotlinCodeActionTooltipTagTest {
	private val actualTags
		get() = KotlinCodeActionsMenu.actions.associate { it.id to it.retrieveTooltipTag(false) }

	@Test
	fun `every kotlin code action maps to its own tooltip tag`() {
		val expected =
			mapOf(
				CommentLineAction.idFor(KT_LANG) to TooltipTag.EDITOR_CODE_ACTIONS_KT_COMMENT,
				UncommentLineAction.idFor(KT_LANG) to TooltipTag.EDITOR_CODE_ACTIONS_KT_UNCOMMENT,
				GoToDefinitionAction.ID to TooltipTag.EDITOR_CODE_ACTIONS_KT_GOTO_DEF,
				FindReferencesAction.ID to TooltipTag.EDITOR_CODE_ACTIONS_KT_FIND_REFS,
				AddImportAction.ID to TooltipTag.EDITOR_CODE_ACTIONS_KT_IMPORT_CLASS,
				OrganizeImportsAction.ID to TooltipTag.EDITOR_CODE_ACTIONS_KT_ORGANIZE_IMPORTS,
				NullSafetyAction.ID to TooltipTag.EDITOR_CODE_ACTIONS_KT_NULL_SAFETY_FIX,
				ImplementMembersAction.ID to TooltipTag.EDITOR_CODE_ACTIONS_KT_IMPLEMENT_MEMBERS,
				SurroundWithTryCatchAction.idFor(KT_LANG) to
					TooltipTag.EDITOR_CODE_ACTIONS_KT_SURROUND_TRY_CATCH,
				ExtractVariableAction.ID to TooltipTag.EDITOR_CODE_ACTIONS_KT_EXTRACT_VARIABLE,
				ExtractMethodAction.ID to TooltipTag.EDITOR_CODE_ACTIONS_KT_EXTRACT_METHOD,
				InlineVariableAction.ID to TooltipTag.EDITOR_CODE_ACTIONS_KT_INLINE_VARIABLE,
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
