package com.itsaky.androidide.lsp.kotlin.actions

import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.get
import com.itsaky.androidide.actions.requireFile
import com.itsaky.androidide.lsp.kotlin.KotlinLanguageServer
import com.itsaky.androidide.lsp.models.CodeActionItem
import com.itsaky.androidide.lsp.models.CodeActionKind
import com.itsaky.androidide.lsp.models.Command
import com.itsaky.androidide.lsp.models.DocumentChange
import com.itsaky.androidide.lsp.models.TextEdit
import com.itsaky.androidide.resources.R
import org.slf4j.LoggerFactory

class OrganizeImportsAction : BaseKotlinCodeAction() {
	override var titleTextRes: Int = R.string.action_organize_imports
	override val id: String = "ide.editor.lsp.kt.organizeImports"
	override var label: String = ""

	companion object {
		private val logger = LoggerFactory.getLogger(OrganizeImportsAction::class.java)
	}

	override suspend fun execAction(data: ActionData): List<TextEdit> {
		// Logic added in Task 5.
		return emptyList()
	}

	override fun postExec(data: ActionData, result: Any) {
		super.postExec(data, result)
		if (result !is List<*> || result.isEmpty()) return

		@Suppress("UNCHECKED_CAST")
		result as List<TextEdit>

		val client = data.languageClient ?: run {
			logger.warn("No language client set. Cannot organize imports.")
			return
		}
		val file = data.requireFile()
		client.performCodeAction(
			CodeActionItem(
				title = label,
				changes = listOf(DocumentChange(file = file.toPath(), edits = result)),
				kind = CodeActionKind.QuickFix,
				command = Command("", ""), // no post-action command (no CMD_FORMAT_CODE)
			)
		)
	}
}
