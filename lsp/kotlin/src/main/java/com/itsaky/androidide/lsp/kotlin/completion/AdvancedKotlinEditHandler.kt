package com.itsaky.androidide.lsp.kotlin.completion

import com.itsaky.androidide.lsp.kotlin.compiler.index.UnpinnedKtFileAccess
import com.itsaky.androidide.lsp.kotlin.utils.AnalysisContext
import com.itsaky.androidide.lsp.models.CompletionItem
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.widget.CodeEditor
import org.jetbrains.kotlin.psi.KtFile
import org.slf4j.LoggerFactory

internal abstract class AdvancedKotlinEditHandler(
	protected val analysisContext: AnalysisContext,
) : BaseKotlinEditHandler() {
	companion object {
		private val logger = LoggerFactory.getLogger(AdvancedKotlinEditHandler::class.java)
	}

	@OptIn(UnpinnedKtFileAccess::class)
	override fun performEdits(
		item: CompletionItem,
		editor: CodeEditor,
		text: Content,
		line: Int,
		column: Int,
		index: Int,
	) {
		// PSI-only, on the UI thread, after completion has already returned: there is no analysis to
		// keep coherent, and pinning here would block the UI thread on a refresh.
		val managedFile = analysisContext.env.ktSymbolIndex.peekLiveKtFile(analysisContext.file)
		if (managedFile == null) {
			logger.error("Unable to perform edit. File not open.")
			return
		}

		context(analysisContext) {
			performEdits(managedFile, editor, item)
		}

		if (item.command != null) {
			executeCommand(editor, item.command)
		}
	}

	context(ctx: AnalysisContext)
	abstract fun performEdits(
		ktFile: KtFile,
		editor: CodeEditor,
		item: CompletionItem,
	)
}
