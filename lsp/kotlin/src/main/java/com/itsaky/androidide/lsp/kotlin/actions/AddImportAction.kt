package com.itsaky.androidide.lsp.kotlin.actions

import android.content.Context
import android.view.View
import android.widget.ListView
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.has
import com.itsaky.androidide.actions.markInvisible
import com.itsaky.androidide.actions.newDialogBuilder
import com.itsaky.androidide.actions.requireFile
import com.itsaky.androidide.idetooltips.TooltipManager
import com.itsaky.androidide.idetooltips.TooltipTag
import com.itsaky.androidide.lsp.api.ILanguageClient
import com.itsaky.androidide.lsp.kotlin.compiler.AbstractCompilationEnvironment
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
import com.itsaky.androidide.utils.applyLongPressRecursively
import com.itsaky.androidide.utils.flashError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path

class AddImportAction : BaseKotlinCodeAction() {
	companion object {
		const val ID = "ide.editor.lsp.kt.diagnostics.addImport"
	}

	override var titleTextRes: Int = R.string.action_import_classes
	override var tooltipTag: String = TooltipTag.EDITOR_CODE_ACTIONS_KT_IMPORT_CLASS

	override val id: String = ID
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

	override suspend fun execAction(data: ActionData): Map<String, List<TextEdit>> {
		val (_, extra) =
			data.findDiagnosticExtra<DiagnosticAction.ResolveReference>()
				?: return emptyMap()

		val (env, action) = extra
		val nioPath = data.requireFile().toPath()
		return withContext(Dispatchers.IO) {
			computeImportCandidates(env, nioPath, action.referenceName)
		}
	}

	/**
	 * Resolves [referenceName] to the importable classifiers known to [env], each mapped to the edits
	 * that import it into the file at [nioPath]. Keyed by fully-qualified name, which is what
	 * [postExec] shows in the chooser -- so two index entries for the same class collapse into one
	 * entry instead of duplicating it.
	 *
	 * Blocking: pinning the file resolves it first, and the index query is SQLite-backed, so callers
	 * must stay off the main thread ([execAction] wraps it in [Dispatchers.IO]).
	 */
	internal fun computeImportCandidates(
		env: AbstractCompilationEnvironment,
		nioPath: Path,
		referenceName: String,
	): Map<String, List<TextEdit>> =
		env.ktSymbolIndex.withLiveKtFile(nioPath) { live ->
			// The index query stays outside `read`, so the disk hit does not hold the project read lock.
			val classifiers =
				env.ktSymbolIndex
					.findSymbolBySimpleName(referenceName, limit = 0)
					.filter { it.kind.isClassifier }

			live.read { ktFile ->
				classifiers.associate { it.fqName to insertImport(ktFile, it.fqName) }
			}
		} ?: emptyMap()

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
				showImportChooser(data, actions, client)
			}
		}
	}

	/**
	 * Shows the import chooser and makes every part of it long-pressable for help.
	 *
	 * [applyLongPressRecursively] skips [ListView] subtrees, so the item list needs its own
	 * listener -- the dialog chrome and the rows are wired separately (ADFA-4510).
	 */
	private fun showImportChooser(
		data: ActionData,
		actions: List<CodeActionItem>,
		client: ILanguageClient,
	) {
		val context = data[Context::class.java] ?: return
		val dialog =
			newDialogBuilder(data)
				.setTitle(label)
				.setItems(actions.map { it.title }.toTypedArray()) { dialog, which ->
					dialog.dismiss()
					actions.getOrNull(which)?.also { client.performCodeAction(it) }
						?: run {
							logger.error("Index $which is out of bounds for actions of size ${actions.size}")
						}
				}.create()

		dialog.listView?.setOnItemLongClickListener { _, view, _, _ ->
			showDialogTooltip(context, view)
			true
		}

		dialog.setOnShowListener {
			val root = dialog.window?.decorView ?: return@setOnShowListener
			root.applyLongPressRecursively {
				showDialogTooltip(context, root)
				true
			}
		}

		dialog.show()
	}

	private fun showDialogTooltip(
		context: Context,
		anchor: View,
	) {
		TooltipManager.showIdeCategoryTooltip(
			context,
			anchor,
			TooltipTag.EDITOR_CODE_ACTIONS_KT_IMPORT_CLASS_DIALOG,
		)
	}
}
