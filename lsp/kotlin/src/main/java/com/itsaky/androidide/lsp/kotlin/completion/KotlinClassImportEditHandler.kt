package com.itsaky.androidide.lsp.kotlin.completion

import com.itsaky.androidide.lsp.kotlin.KtFileManager
import com.itsaky.androidide.lsp.kotlin.utils.AnalysisContext
import com.itsaky.androidide.lsp.kotlin.utils.insertImport
import com.itsaky.androidide.lsp.models.ClassCompletionData
import com.itsaky.androidide.lsp.models.CompletionItem
import com.itsaky.androidide.lsp.util.RewriteHelper
import io.github.rosemoe.sora.widget.CodeEditor

internal class KotlinClassImportEditHandler(
	analysisContext: AnalysisContext,
) : AdvancedKotlinEditHandler(analysisContext) {
	override fun performEdits(
		managedFile: KtFileManager.ManagedFile,
		editor: CodeEditor,
		item: CompletionItem
	) {
		val data = item.data as? ClassCompletionData ?: return
		context(analysisContext) {
			val edits = insertImport(data.className)
			if (edits.isNotEmpty()) {
				RewriteHelper.performEdits(edits, editor)
			}
		}
	}
}