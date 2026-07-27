package com.itsaky.androidide.lsp.kotlin.actions

import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.has
import com.itsaky.androidide.actions.markInvisible
import com.itsaky.androidide.actions.newDialogBuilder
import com.itsaky.androidide.actions.requireFile
import com.itsaky.androidide.idetooltips.TooltipTag
import com.itsaky.androidide.lsp.kotlin.compiler.index.findSymbolBySimpleName
import com.itsaky.androidide.lsp.kotlin.diagnostic.DiagnosticAction
import com.itsaky.androidide.lsp.kotlin.utils.insertImport
import com.itsaky.androidide.lsp.models.CodeActionItem
import com.itsaky.androidide.lsp.models.CodeActionKind
import com.itsaky.androidide.lsp.models.Command
import com.itsaky.androidide.lsp.models.DiagnosticItem
import com.itsaky.androidide.lsp.models.DocumentChange
import com.itsaky.androidide.lsp.models.TextEdit
import com.itsaky.androidide.resources.R
import com.itsaky.androidide.utils.flashError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.appdevforall.codeonthego.indexing.jvm.JvmSymbol

class AddImportAction : BaseKotlinCodeAction() {
	override var titleTextRes: Int = R.string.action_import_classes
	override var tooltipTag: String = TooltipTag.EDITOR_CODE_ACTIONS_KT_FIX_IMPORTS

	override val id: String = "ide.editor.lsp.kt.diagnostics.addImport"
	override var label: String = ""

	override fun prepare(data: ActionData) {
		super.prepare(data)

		if (!visible || !data.has<DiagnosticItem>()) {
			markInvisible()
			return
		}

		// Optimistic visibility: decide from the in-memory unresolved-reference marker only. The
		// importable-classifier resolution runs in the background execAction; doing it here would be
		// main-thread SQLite I/O, because fillMenu() calls prepare() synchronously on the UI thread.
		val resolveReferenceActionDiagnostic =
			data.findDiagnosticExtra<DiagnosticAction.ResolveReference>()
		if (resolveReferenceActionDiagnostic == null) {
			markInvisible()
			return
		}
	}

	override suspend fun execAction(data: ActionData): Map<JvmSymbol, List<TextEdit>> {
		val (_, extra) =
			data.findDiagnosticExtra<DiagnosticAction.ResolveReference>()
				?: return emptyMap()

		val (env, action) = extra
		val file = data.requireFile()
		val nioPath = file.toPath()
		val ktFile =
			withContext(Dispatchers.IO) {
				env.ktSymbolIndex
					.getCurrentKtFile(nioPath)
					.get()
			}
				?: return emptyMap()

		return env.ktSymbolIndex
			.findSymbolBySimpleName(action.referenceName, limit = 0)
			.filter { it.kind.isClassifier }
			.associateWith { symbol -> insertImport(ktFile, symbol.fqName) }
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
		result as Map<JvmSymbol, List<TextEdit>>

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
				.map { (symbol, edits) ->
					CodeActionItem(
						title = symbol.fqName,
						changes = listOf(DocumentChange(file = nioPath, edits = edits)),
						kind = CodeActionKind.QuickFix,
						command = Command.CMD_FORMAT_CODE,
					)
				}

		when (actions.size) {
			0 -> {
				logger.error("No code actions found. Cannot completion action.")
			}

			1 -> {
				client.performCodeAction(actions[0])
			}

			else -> {
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
}
