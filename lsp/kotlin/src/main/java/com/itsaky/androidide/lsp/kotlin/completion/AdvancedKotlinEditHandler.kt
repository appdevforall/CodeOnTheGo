package com.itsaky.androidide.lsp.kotlin.completion

import com.itsaky.androidide.lsp.kotlin.KtFileManager
import com.itsaky.androidide.lsp.kotlin.utils.AnalysisContext
import com.itsaky.androidide.lsp.models.CompletionItem
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.widget.CodeEditor
import org.slf4j.LoggerFactory

internal abstract class AdvancedKotlinEditHandler(
	protected val analysisContext: AnalysisContext,
) : BaseKotlinEditHandler() {

	companion object {
		private val logger = LoggerFactory.getLogger(AdvancedKotlinEditHandler::class.java)
	}

	override fun performEdits(
		item: CompletionItem,
		editor: CodeEditor,
		text: Content,
		line: Int,
		column: Int,
		index: Int
	) {
		val managedFile = analysisContext.env.fileManager.getOpenFile(analysisContext.file)
		if (managedFile == null) {
			logger.error("Unable to perform edit. File not open.")
			return
		}

		performEdits(managedFile, editor, item)
		if (item.command != null) {
			executeCommand(editor, item.command)
		}
	}

	abstract fun performEdits(
		managedFile: KtFileManager.ManagedFile,
		editor: CodeEditor,
		item: CompletionItem
	)
}