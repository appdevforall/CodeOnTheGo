package com.itsaky.androidide.lsp.kotlin.codeActions

import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.requireEditor
import com.itsaky.androidide.lsp.kotlin.BasicKotlinCodeAction
import com.itsaky.androidide.resources.R

class KCommentAction : BasicKotlinCodeAction() {
    override val id: String = "ide.editor.lsp.java.commentLine"
    override var label: String = ""

    override val titleTextRes: Int = R.string.action_comment_line

    override var requiresUIThread: Boolean = true

    override suspend fun execAction(data: ActionData): Boolean {
        val editor = data.requireEditor()
        val text = editor.text
        val cursor = editor.cursor

        text.beginBatchEdit()
        for (line in cursor.leftLine..cursor.rightLine) {
            text.insert(line, 0, "//")
        }
        text.endBatchEdit()

        return true
    }

    override fun dismissOnAction() = false
}
