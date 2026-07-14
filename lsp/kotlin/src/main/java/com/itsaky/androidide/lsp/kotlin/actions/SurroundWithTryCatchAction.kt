package com.itsaky.androidide.lsp.kotlin.actions

import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.requireEditor
import com.itsaky.androidide.actions.requireFile
import com.itsaky.androidide.idetooltips.TooltipTag
import com.itsaky.androidide.lsp.kotlin.utils.computeSurroundWithTryCatchEdit
import com.itsaky.androidide.lsp.models.CodeActionItem
import com.itsaky.androidide.lsp.models.CodeActionKind
import com.itsaky.androidide.lsp.models.Command
import com.itsaky.androidide.lsp.models.DocumentChange
import com.itsaky.androidide.lsp.models.TextEdit
import com.itsaky.androidide.resources.R

class SurroundWithTryCatchAction : BaseKotlinCodeAction() {
	override var titleTextRes: Int = R.string.action_surround_with_try_catch
	override var tooltipTag: String = TooltipTag.EDITOR_CODE_ACTIONS_SURROUND_TRY_CATCH

	override val id: String = "ide.editor.lsp.kt.surroundWithTryCatch"
	override var label: String = ""

	// Reads the editor selection, so it must run on the UI thread (as CommentLineAction does).
	override var requiresUIThread: Boolean = true

	override suspend fun execAction(data: ActionData): List<TextEdit> {
		val editor = data.requireEditor()
		val cursor = editor.cursor
		val edit =
			computeSurroundWithTryCatchEdit(
				editor.text.toString(),
				cursor.leftLine,
				cursor.rightLine,
			) ?: return emptyList()
		return listOf(edit)
	}

	override fun postExec(data: ActionData, result: Any) {
		super.postExec(data, result)

		if (result !is List<*> || result.isEmpty()) {
			return
		}

		@Suppress("UNCHECKED_CAST")
		val edits = result as List<TextEdit>

		val client =
			data.languageClient
				?: run {
					logger.warn("No language client set. Cannot complete action.")
					return
				}

		val file = data.requireFile()
		client.performCodeAction(
			CodeActionItem(
				title = label,
				changes = listOf(DocumentChange(file = file.toPath(), edits = edits)),
				kind = CodeActionKind.QuickFix,
				command = Command.CMD_FORMAT_CODE,
			)
		)
	}
}
