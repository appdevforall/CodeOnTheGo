package com.itsaky.androidide.lsp.kotlin.codeActions

import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.hasRequiredData
import com.itsaky.androidide.actions.markInvisible
import com.itsaky.androidide.editor.api.ILspEditor
import com.itsaky.androidide.lsp.kotlin.BasicKotlinCodeAction
import com.itsaky.androidide.resources.R
import io.github.rosemoe.sora.widget.CodeEditor
import java.io.File

class KGoToDefinitionAction : BasicKotlinCodeAction() {

    override val titleTextRes: Int = R.string.action_goto_definition
    override val id: String = "ide.editor.lsp.java.gotoDefinition"
    override var label: String = ""
    override var requiresUIThread: Boolean = true

    override fun prepare(data: ActionData) {
        super.prepare(data)

        if (!visible || !data.hasRequiredData(CodeEditor::class.java, File::class.java)) {
            markInvisible()
            return
        }
    }

    override suspend fun execAction(data: ActionData): Any {
        val editor = data[CodeEditor::class.java]!!
        return (editor as? ILspEditor)?.findDefinition() ?: false
    }
}
