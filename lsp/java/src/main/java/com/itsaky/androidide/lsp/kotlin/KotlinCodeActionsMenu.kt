package com.itsaky.androidide.lsp.kotlin

import com.itsaky.androidide.actions.ActionItem
import com.itsaky.androidide.lsp.actions.IActionsMenuProvider
import com.itsaky.androidide.lsp.kotlin.codeActions.KCommentAction
import com.itsaky.androidide.lsp.kotlin.codeActions.KFindReferencesAction
import com.itsaky.androidide.lsp.kotlin.codeActions.KGoToDefinitionAction
import com.itsaky.androidide.lsp.kotlin.codeActions.KUncommentAction

class KotlinCodeActionsMenu : IActionsMenuProvider {
    override val actions: List<ActionItem> =
        listOf(
            KFindReferencesAction(),
            KCommentAction(),
            KGoToDefinitionAction(),
            KUncommentAction()
        )
}