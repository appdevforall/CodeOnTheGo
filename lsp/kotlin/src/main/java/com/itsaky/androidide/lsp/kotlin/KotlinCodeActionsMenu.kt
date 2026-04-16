package com.itsaky.androidide.lsp.kotlin

import com.itsaky.androidide.actions.ActionItem
import com.itsaky.androidide.lsp.actions.CommentLineAction
import com.itsaky.androidide.lsp.actions.IActionsMenuProvider
import com.itsaky.androidide.lsp.actions.UncommentLineAction

object KotlinCodeActionsMenu : IActionsMenuProvider {

	private const val KT_LANG = "kt"
	private val KT_EXTS = listOf("kt", "kts")
	private const val KT_LINE_COMMENT_TOKEN = "//"

	override val actions: List<ActionItem> =
		listOf(
			CommentLineAction(KT_LANG, KT_EXTS, KT_LINE_COMMENT_TOKEN),
			UncommentLineAction(KT_LANG, KT_EXTS, KT_LINE_COMMENT_TOKEN)
		)
}