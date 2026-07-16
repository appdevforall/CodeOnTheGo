package com.itsaky.androidide.lsp.kotlin.actions

import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.get
import com.itsaky.androidide.actions.requireFile
import com.itsaky.androidide.lsp.kotlin.KotlinLanguageServer
import com.itsaky.androidide.lsp.kotlin.compiler.AbstractCompilationEnvironment
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
import java.nio.file.Path

class OrganizeImportsAction : BaseKotlinCodeAction() {
	override var titleTextRes: Int = R.string.action_organize_imports
	override val id: String = "ide.editor.lsp.kt.organizeImports"
	override var label: String = ""

	companion object {
		private val logger = LoggerFactory.getLogger(OrganizeImportsAction::class.java)
	}

	override suspend fun execAction(data: ActionData): List<TextEdit> {
		val server = data.get<KotlinLanguageServer>() ?: return emptyList()
		val nioPath = data.requireFile().toPath()
		val env = server.compilationEnvironmentFor(nioPath) ?: return emptyList()
		return computeOrganizeEdit(env, nioPath)
	}

	/**
	 * Computes the text edits that organize the imports of the file at [nioPath] within [env].
	 * The current [org.jetbrains.kotlin.psi.KtFile] is fetched BEFORE entering [read] (deadlock
	 * rule: never block on `getCurrentKtFile(...).get()` inside `project.read`). Returns an empty
	 * list when there is nothing to do (no imports, already organized, or no usable range) *and*
	 * whenever anything in this pipeline (the `.get()`, analysis, or PSI access) throws: the action
	 * framework only catches [IllegalArgumentException] and this runs on a coroutine scope with no
	 * exception handler, so an uncaught throw here would crash the app. Degrading to zero edits is
	 * always safe -- it just leaves the imports as-is, never produces a partial/incorrect rewrite.
	 */
	internal fun computeOrganizeEdit(
		env: AbstractCompilationEnvironment,
		nioPath: Path,
	): List<TextEdit> =
		runCatching {
			val ktFile = env.ktSymbolIndex.getCurrentKtFile(nioPath).get() ?: return emptyList()
			if (ktFile.importDirectives.isEmpty()) return emptyList()
			env.project.read {
				val usage = analyzeMaybeDangling(ktFile) { collectImportUsage(ktFile) }
				val newText = organizedImportBlock(ktFile, usage) ?: return@read emptyList()
				val range = ktFile.importList?.textRange?.toRange(ktFile) ?: return@read emptyList()
				if (range == Range.NONE) return@read emptyList()
				listOf(TextEdit(range, newText))
			}
		}.getOrElse { e ->
			logger.warn("Failed to organize imports", e)
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
