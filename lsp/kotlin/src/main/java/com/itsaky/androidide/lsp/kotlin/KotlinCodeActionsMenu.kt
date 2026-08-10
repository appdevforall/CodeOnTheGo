package com.itsaky.androidide.lsp.kotlin

import com.itsaky.androidide.actions.ActionItem
import com.itsaky.androidide.idetooltips.TooltipTag
import com.itsaky.androidide.lsp.actions.CommentLineAction
import com.itsaky.androidide.lsp.actions.IActionsMenuProvider
import com.itsaky.androidide.lsp.actions.SurroundWithTryCatchAction
import com.itsaky.androidide.lsp.actions.UncommentLineAction
import com.itsaky.androidide.lsp.kotlin.actions.AddImportAction
import com.itsaky.androidide.lsp.kotlin.actions.ExtractVariableAction
import com.itsaky.androidide.lsp.kotlin.actions.FindReferencesAction
import com.itsaky.androidide.lsp.kotlin.actions.GoToDefinitionAction
import com.itsaky.androidide.lsp.kotlin.actions.ImplementMembersAction
import com.itsaky.androidide.lsp.kotlin.actions.NullSafetyAction
import com.itsaky.androidide.lsp.kotlin.actions.OrganizeImportsAction

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
		)
}
