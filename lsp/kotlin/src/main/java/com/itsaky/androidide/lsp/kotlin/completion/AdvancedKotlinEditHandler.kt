package com.itsaky.androidide.lsp.kotlin.completion

import com.itsaky.androidide.lsp.kotlin.compiler.index.UnpinnedKtFileAccess
import com.itsaky.androidide.lsp.kotlin.compiler.read
import com.itsaky.androidide.lsp.kotlin.utils.AnalysisContext
import com.itsaky.androidide.lsp.models.CompletionItem
import com.itsaky.androidide.lsp.models.TextEdit
import com.itsaky.androidide.lsp.util.RewriteHelper
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
		// keep coherent, and pinning here would block the UI thread on a refresh. The read lock still
		// matters: an in-place refresh mutates this same instance under project.write. The editor is
		// changed only after the lock is released, so no listener it fires can run under it.
		val env = analysisContext.env
		val edits =
			env.project.read {
				env.ktSymbolIndex.peekLiveKtFile(analysisContext.file)?.let { managedFile ->
					context(analysisContext) { computeEdits(managedFile, item) }
				}
			}
		if (edits == null) {
			logger.error("Unable to perform edit. File not open.")
			return
		}

		if (edits.isNotEmpty()) {
			RewriteHelper.performEdits(edits, editor)
		}

		if (item.command != null) {
			executeCommand(editor, item.command)
		}
	}

	/** The edits applying [item] makes to [ktFile]; runs under project.read, so it must not touch the editor. */
	context(ctx: AnalysisContext)
	abstract fun computeEdits(
		ktFile: KtFile,
		item: CompletionItem,
	): List<TextEdit>
}
