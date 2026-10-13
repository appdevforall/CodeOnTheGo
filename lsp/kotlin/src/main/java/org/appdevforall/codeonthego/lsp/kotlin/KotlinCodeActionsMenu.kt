package org.appdevforall.codeonthego.lsp.kotlin

import org.appdevforall.codeonthego.actions.ActionItem
import org.appdevforall.codeonthego.idetooltips.TooltipTag
import org.appdevforall.codeonthego.lsp.actions.CommentLineAction
import org.appdevforall.codeonthego.lsp.actions.IActionsMenuProvider
import org.appdevforall.codeonthego.lsp.actions.SurroundWithTryCatchAction
import org.appdevforall.codeonthego.lsp.actions.UncommentLineAction
import org.appdevforall.codeonthego.lsp.kotlin.actions.AddImportAction
import org.appdevforall.codeonthego.lsp.kotlin.actions.ExtractMethodAction
import org.appdevforall.codeonthego.lsp.kotlin.actions.ExtractVariableAction
import org.appdevforall.codeonthego.lsp.kotlin.actions.FindReferencesAction
import org.appdevforall.codeonthego.lsp.kotlin.actions.GoToDefinitionAction
import org.appdevforall.codeonthego.lsp.kotlin.actions.ImplementMembersAction
import org.appdevforall.codeonthego.lsp.kotlin.actions.InlineVariableAction
import org.appdevforall.codeonthego.lsp.kotlin.actions.NullSafetyAction
import org.appdevforall.codeonthego.lsp.kotlin.actions.OrganizeImportsAction

object KotlinCodeActionsMenu : IActionsMenuProvider {
	internal const val KT_LANG = "kt"
	private val KT_EXTS = listOf("kt", "kts")
	private const val KT_LINE_COMMENT_TOKEN = "//"
	private const val KT_CATCH_CLAUSE = "catch (e: Exception)"
	private const val KT_CATCH_BODY = "e.printStackTrace()"

	override val actions: List<ActionItem> =
		listOf(
			CommentLineAction(
				KT_LANG,
				KT_EXTS,
				KT_LINE_COMMENT_TOKEN,
				TooltipTag.EDITOR_CODE_ACTIONS_KT_COMMENT,
			),
			UncommentLineAction(
				KT_LANG,
				KT_EXTS,
				KT_LINE_COMMENT_TOKEN,
				TooltipTag.EDITOR_CODE_ACTIONS_KT_UNCOMMENT,
			),
			GoToDefinitionAction(),
			FindReferencesAction(),
			AddImportAction(),
			OrganizeImportsAction(),
			SurroundWithTryCatchAction(
				KT_LANG,
				KT_EXTS,
				KotlinLanguageServer.SERVER_ID,
				KT_CATCH_CLAUSE,
				KT_CATCH_BODY,
				TooltipTag.EDITOR_CODE_ACTIONS_KT_SURROUND_TRY_CATCH,
			),
			NullSafetyAction(),
			ImplementMembersAction(),
			ExtractVariableAction(),
			ExtractMethodAction(),
			InlineVariableAction(),
		)
}
