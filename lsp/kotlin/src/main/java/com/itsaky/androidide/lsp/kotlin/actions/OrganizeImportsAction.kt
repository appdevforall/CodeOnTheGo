package com.itsaky.androidide.lsp.kotlin.actions

import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.get
import com.itsaky.androidide.actions.requireFile
import com.itsaky.androidide.lsp.kotlin.KotlinLanguageServer
import com.itsaky.androidide.lsp.kotlin.compiler.modules.analyzeMaybeDangling
import com.itsaky.androidide.lsp.kotlin.compiler.read
import com.itsaky.androidide.lsp.kotlin.utils.collectImportUsage
import com.itsaky.androidide.lsp.kotlin.utils.organizedImportBlock
import com.itsaky.androidide.lsp.kotlin.utils.toRange
import com.itsaky.androidide.lsp.models.CodeActionItem
import com.itsaky.androidide.lsp.models.CodeActionKind
import com.itsaky.androidide.lsp.models.Command
import com.itsaky.androidide.lsp.models.DocumentChange
import com.itsaky.androidide.lsp.models.TextEdit
import com.itsaky.androidide.models.Range
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
		val server = data.get<KotlinLanguageServer>() ?: return emptyList()
		val file = data.requireFile()
		val nioPath = file.toPath()

		val env = server.compilationEnvironmentFor(nioPath) ?: return emptyList()

		// Fetch the current KtFile BEFORE entering project.read (deadlock rule).
		val ktFile = env.ktSymbolIndex.getCurrentKtFile(nioPath).get() ?: return emptyList()
		if (ktFile.importDirectives.isEmpty()) return emptyList()

		return env.project.read {
			val usage = analyzeMaybeDangling(ktFile) { collectImportUsage(ktFile) }
			val newText = organizedImportBlock(ktFile, usage) ?: return@read emptyList()
			val range = ktFile.importList?.textRange?.toRange(ktFile) ?: return@read emptyList()
			if (range == Range.NONE) return@read emptyList()
			listOf(TextEdit(range, newText))
		}
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
