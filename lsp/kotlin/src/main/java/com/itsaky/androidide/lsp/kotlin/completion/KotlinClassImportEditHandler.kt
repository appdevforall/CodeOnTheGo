package com.itsaky.androidide.lsp.kotlin.completion

import com.itsaky.androidide.lsp.kotlin.utils.AnalysisContext
import com.itsaky.androidide.lsp.kotlin.utils.insertImport
import com.itsaky.androidide.lsp.models.ClassCompletionData
import com.itsaky.androidide.lsp.models.CompletionItem
import com.itsaky.androidide.lsp.util.RewriteHelper
import io.github.rosemoe.sora.widget.CodeEditor
import org.jetbrains.kotlin.psi.KtFile

internal class KotlinClassImportEditHandler(
	analysisContext: AnalysisContext,
) : AdvancedKotlinEditHandler(analysisContext) {
	override fun performEdits(
		ktFile: KtFile,
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