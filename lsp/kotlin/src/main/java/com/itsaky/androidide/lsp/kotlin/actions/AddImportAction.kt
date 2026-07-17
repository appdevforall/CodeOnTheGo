package com.itsaky.androidide.lsp.kotlin.actions

import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.has
import com.itsaky.androidide.actions.markInvisible
import com.itsaky.androidide.actions.newDialogBuilder
import com.itsaky.androidide.actions.require
import com.itsaky.androidide.actions.requireFile
import com.itsaky.androidide.idetooltips.TooltipTag
import com.itsaky.androidide.lsp.kotlin.compiler.AbstractCompilationEnvironment
import com.itsaky.androidide.lsp.kotlin.compiler.index.findSymbolBySimpleName
import com.itsaky.androidide.lsp.kotlin.compiler.read
import com.itsaky.androidide.lsp.kotlin.diagnostic.KotlinDiagnosticExtra
import com.itsaky.androidide.lsp.kotlin.utils.insertImport
import com.itsaky.androidide.lsp.models.CodeActionItem
import com.itsaky.androidide.lsp.models.CodeActionKind
import com.itsaky.androidide.lsp.models.Command
import com.itsaky.androidide.lsp.models.DiagnosticItem
import com.itsaky.androidide.lsp.models.DocumentChange
import com.itsaky.androidide.lsp.models.TextEdit
import com.itsaky.androidide.resources.R
import com.itsaky.androidide.utils.flashError
import org.slf4j.LoggerFactory
import java.nio.file.Path

class AddImportAction : BaseKotlinCodeAction() {
	override var titleTextRes: Int = R.string.action_import_classes
	override var tooltipTag: String = TooltipTag.EDITOR_CODE_ACTIONS_FIX_IMPORTS

	override val id: String = "ide.editor.lsp.kt.diagnostics.addImport"
	override var label: String = ""

	companion object {
		private val logger = LoggerFactory.getLogger(AddImportAction::class.java)
	}

	override fun prepare(data: ActionData) {
		super.prepare(data)

		if (!visible || !data.has<DiagnosticItem>()) {
			markInvisible()
			return
		}

		// Optimistic visibility: decide from the in-memory unresolved-reference marker only. The
		// importable-classifier resolution runs in the background execAction; doing it here would be
		// main-thread SQLite I/O, because fillMenu() calls prepare() synchronously on the UI thread.
		val extra = data.require<DiagnosticItem>().extra as? KotlinDiagnosticExtra
		if (extra?.unresolvedReference == null) {
			markInvisible()
			return
		}
	}

	override suspend fun execAction(data: ActionData): Map<String, List<TextEdit>> {
		val (reference, env) =
			data.require<DiagnosticItem>().extra as? KotlinDiagnosticExtra
				?: return emptyMap()

		if (reference == null) return emptyMap()

		return computeImportCandidates(env, data.requireFile().toPath(), reference)
	}

	/**
	 * Computes, for the unresolved [reference] in the file at [nioPath] within [env], a map from
	 * each importable classifier's fully-qualified name to the edits that add its import in sorted
	 * position. The [org.jetbrains.kotlin.psi.KtFile] is fetched BEFORE entering [read] (deadlock
	 * rule: never block on `getCurrentKtFile(...).get()` inside `project.read`). Keying by FQN
	 * collapses the duplicate a symbol picks up from being present in both the source and library
	 * indexes. Returns an empty map when there is nothing to import *and* whenever anything in this
	 * pipeline throws: the action framework only catches [IllegalArgumentException] and this runs on
	 * a coroutine scope with no exception handler, so an uncaught throw here would crash the app.
	 */
	internal fun computeImportCandidates(
		env: AbstractCompilationEnvironment,
		nioPath: Path,
		reference: String,
	): Map<String, List<TextEdit>> =
		runCatching {
			val ktFile = env.ktSymbolIndex.getCurrentKtFile(nioPath).get() ?: return emptyMap()
			env.project.read {
				env.ktSymbolIndex
					.findSymbolBySimpleName(reference, limit = 0)
					.filter { it.kind.isClassifier }
					.associate { symbol -> symbol.fqName to insertImport(ktFile, symbol.fqName) }
			}
		}.getOrElse { e ->
			logger.warn("Failed to compute import candidates for '{}'", reference, e)
			emptyMap()
		}

	override fun postExec(
		data: ActionData,
		result: Any,
	) {
		super.postExec(data, result)

		if (result !is Map<*, *>) {
			return
		}

		@Suppress("UNCHECKED_CAST")
		result as Map<String, List<TextEdit>>

		if (result.isEmpty()) {
			logger.warn("No classifiers to import.")
			flashError(R.string.msg_no_imports_found)
			return
		}

		val client =
			data.languageClient
				?: run {
					logger.warn("No language client set. Cannot complete action.")
					return
				}

		val file = data.requireFile()
		val nioPath = file.toPath()
		val actions =
			result
				.map { (fqName, edits) ->
					CodeActionItem(
						title = fqName,
						changes = listOf(DocumentChange(file = nioPath, edits = edits)),
						kind = CodeActionKind.QuickFix,
						// Imports are column-0 text; emit final text ourselves. CMD_FORMAT_CODE is a
						// no-op for Kotlin, so use an empty (no-op) post-action command.
						command = Command("", ""),
					)
				}

		when (actions.size) {
			0 -> logger.error("No code actions found. Cannot completion action.")
			1 -> client.performCodeAction(actions[0])
			else ->
				newDialogBuilder(data)
					.setTitle(label)
					.setItems(actions.map { it.title }.toTypedArray()) { dialog, which ->
						dialog.dismiss()
						actions.getOrNull(which)?.also { client.performCodeAction(it) }
							?: run {
								logger.error("Index $which is out of bounds for actions of size ${actions.size}")
							}
					}.show()
		}
	}
}
