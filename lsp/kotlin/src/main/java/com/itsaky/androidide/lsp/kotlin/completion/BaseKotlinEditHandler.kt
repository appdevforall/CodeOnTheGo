package com.itsaky.androidide.lsp.kotlin.completion

import com.itsaky.androidide.editor.api.ILspEditor
import com.itsaky.androidide.lsp.edits.DefaultEditHandler
import com.itsaky.androidide.lsp.models.Command
import io.github.rosemoe.sora.widget.CodeEditor

/**
 * Implementation of [DefaultEditHandler] which avoids reflection in
 * [DefaultEditHandler.executeCommand].
 *
 * @author Akash Yadav
 */
open class BaseKotlinEditHandler : DefaultEditHandler() {

	override fun executeCommand(editor: CodeEditor, command: Command?) {
		if (editor is ILspEditor) {
			editor.executeCommand(command)
			return
		}
		super.executeCommand(editor, command)
	}
}
