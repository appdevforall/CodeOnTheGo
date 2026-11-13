package com.itsaky.androidide.lsp.kotlin.completion

import com.itsaky.androidide.lsp.kotlin.utils.AnalysisContext
import com.itsaky.androidide.lsp.kotlin.utils.insertImport
import com.itsaky.androidide.lsp.models.ClassCompletionData
import com.itsaky.androidide.lsp.models.CompletionItem
import com.itsaky.androidide.lsp.models.TextEdit
import org.appdevforall.codeonthego.indexing.jvm.JvmSymbol
import org.jetbrains.kotlin.psi.KtFile

internal class KotlinAutoImportEditHandler(
	analysisContext: AnalysisContext,
	private val symbolToImport: JvmSymbol? = null,
) : AdvancedKotlinEditHandler(analysisContext) {

	context(ctx: AnalysisContext)
	override fun computeEdits(
		ktFile: KtFile,
		item: CompletionItem
	): List<TextEdit> {
		val fqnToImport =
			symbolToImport?.fqName
				?: (item.data as? ClassCompletionData)?.className
				?: return emptyList()

		return insertImport(ktFile, fqnToImport)
	}
}
