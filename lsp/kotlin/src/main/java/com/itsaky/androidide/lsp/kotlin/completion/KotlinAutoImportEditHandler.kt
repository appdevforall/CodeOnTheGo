package com.itsaky.androidide.lsp.kotlin.completion

import com.itsaky.androidide.lsp.kotlin.utils.AnalysisContext
import com.itsaky.androidide.lsp.kotlin.utils.insertImport
import com.itsaky.androidide.lsp.models.ClassCompletionData
import com.itsaky.androidide.lsp.models.CompletionItem
import com.itsaky.androidide.lsp.util.RewriteHelper
import io.github.rosemoe.sora.widget.CodeEditor
import org.appdevforall.codeonthego.indexing.jvm.JvmSymbol
import org.jetbrains.kotlin.psi.KtFile

internal class KotlinAutoImportEditHandler(
	analysisContext: AnalysisContext,
	private val symbolToImport: JvmSymbol? = null,
) : AdvancedKotlinEditHandler(analysisContext) {

	context(ctx: AnalysisContext)
	override fun performEdits(
		ktFile: KtFile,
		editor: CodeEditor,
		item: CompletionItem
	) {
		val fqnToImport =
			symbolToImport?.fqName
				?: (item.data as? ClassCompletionData)?.className
				?: return

		val edits = insertImport(ktFile, fqnToImport)
		if (edits.isNotEmpty()) {
			RewriteHelper.performEdits(edits, editor)
		}
	}
}