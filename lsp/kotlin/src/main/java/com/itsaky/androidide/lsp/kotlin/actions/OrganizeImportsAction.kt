package com.itsaky.androidide.lsp.kotlin.actions

import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.get
import com.itsaky.androidide.actions.requireFile
import com.itsaky.androidide.idetooltips.TooltipTag
import com.itsaky.androidide.lsp.kotlin.KotlinLanguageServer
import com.itsaky.androidide.lsp.kotlin.compiler.AbstractCompilationEnvironment
import com.itsaky.androidide.lsp.kotlin.compiler.modules.AnalysisPriority
import com.itsaky.androidide.lsp.kotlin.compiler.modules.isAnalysisCancellation
import com.itsaky.androidide.lsp.kotlin.compiler.modules.retryingOnPreemption
import com.itsaky.androidide.lsp.kotlin.utils.collectImportUsage
import com.itsaky.androidide.lsp.kotlin.utils.organizedImportBlock
import com.itsaky.androidide.lsp.kotlin.utils.toRange
import com.itsaky.androidide.lsp.models.CodeActionItem
import com.itsaky.androidide.lsp.models.CodeActionKind
import com.itsaky.androidide.lsp.models.Command
import com.itsaky.androidide.lsp.models.DocumentChange
import com.itsaky.androidide.lsp.models.TextEdit
import com.itsaky.androidide.models.Range
import com.itsaky.androidide.progress.ICancelChecker
import com.itsaky.androidide.resources.R
import com.itsaky.androidide.tasks.createJobCancelChecker
import org.slf4j.LoggerFactory
import java.nio.file.Path

class OrganizeImportsAction : BaseKotlinCodeAction() {
	override var titleTextRes: Int = R.string.action_organize_imports
	override val id: String = ID
	override var label: String = ""
	override var tooltipTag: String = TooltipTag.EDITOR_CODE_ACTIONS_KT_ORGANIZE_IMPORTS

	companion object {
		const val ID = "ide.editor.lsp.kt.organizeImports"

		private val logger = LoggerFactory.getLogger(OrganizeImportsAction::class.java)
	}

	override suspend fun execAction(data: ActionData): List<TextEdit> {
		val server = data.get<KotlinLanguageServer>() ?: return emptyList()
		val nioPath = data.requireFile().toPath()
		val env = server.compilationEnvironmentFor(nioPath) ?: return emptyList()
		// Ties the analysis to this action's coroutine: cancelling the action aborts the queued analysis.
		return computeOrganizeEdit(env, nioPath, createJobCancelChecker())
	}

	/**
	 * Computes the text edits that organize the imports of the file at [nioPath] within [env].
	 *
	 * Returns an empty list when there is nothing to do (no imports, already organized, or no usable
	 * range) *and* whenever anything in this pipeline (acquisition, analysis, or PSI access) throws: the
	 * action framework only catches [IllegalArgumentException] and this runs on a coroutine scope with no
	 * exception handler, so an uncaught throw here would crash the app. Degrading to zero edits is
	 * always safe -- it just leaves the imports as-is, never produces a partial/incorrect rewrite.
	 */
	internal fun computeOrganizeEdit(
		env: AbstractCompilationEnvironment,
		nioPath: Path,
		cancelChecker: ICancelChecker,
	): List<TextEdit> =
		runCatching {
			// A user-invoked command: AnalysisPriority.COMMAND, retried once if keystroke-driven work
			// preempts it (ADR 0011). Without the retry a preemption fell into the getOrElse below and
			// organize-imports silently did nothing. The file is re-pinned per attempt because the
			// preemptor also refreshed the live PSI.
			retryingOnPreemption(cancelChecker, "Organize imports for $nioPath") { checker ->
				env.ktSymbolIndex.withLiveKtFile(nioPath) { live ->
					live.read { ktFile ->
						if (ktFile.importDirectives.isEmpty()) return@read emptyList()
						val usage = live.analyzing(AnalysisPriority.COMMAND, checker) { collectImportUsage(it) }
						val newText = organizedImportBlock(ktFile, usage) ?: return@read emptyList()
						val range = ktFile.importList?.textRange?.toRange(ktFile) ?: return@read emptyList()
						if (range == Range.NONE) return@read emptyList()
						listOf(TextEdit(range, newText))
					}
				} ?: emptyList()
			}
		}.getOrElse { e ->
			if (e.isAnalysisCancellation()) {
				// Cancelled, or preempted past the retry above: not a failure, and warn-logging it would
				// bury the ones that are.
				logger.debug("Organize imports for {} was cancelled", nioPath, e)
			} else {
				logger.warn("Failed to organize imports", e)
			}
			emptyList()
		}

	override fun postExec(
		data: ActionData,
		result: Any,
	) {
		super.postExec(data, result)
		if (result !is List<*> || result.isEmpty()) return

		@Suppress("UNCHECKED_CAST")
		result as List<TextEdit>

		val client =
			data.languageClient ?: run {
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
			),
		)
	}
}
