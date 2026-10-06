package org.appdevforall.codeonthego.lsp.kotlin.completion

import org.appdevforall.codeonthego.editor.api.ILspEditor
import org.appdevforall.codeonthego.lsp.edits.DefaultEditHandler
import org.appdevforall.codeonthego.lsp.models.Command
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
