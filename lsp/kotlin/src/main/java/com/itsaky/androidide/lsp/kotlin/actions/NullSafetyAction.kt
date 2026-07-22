package com.itsaky.androidide.lsp.kotlin.actions

import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.get
import com.itsaky.androidide.actions.markInvisible
import com.itsaky.androidide.actions.newDialogBuilder
import com.itsaky.androidide.actions.requireContext
import com.itsaky.androidide.actions.requireFile
import com.itsaky.androidide.lsp.kotlin.compiler.read
import com.itsaky.androidide.lsp.kotlin.diagnostic.DiagnosticAction
import com.itsaky.androidide.lsp.kotlin.diagnostic.KotlinDiagnosticExtra
import com.itsaky.androidide.lsp.kotlin.utils.NullSafetyKind
import com.itsaky.androidide.lsp.kotlin.utils.NullSafetyVariant
import com.itsaky.androidide.lsp.kotlin.utils.findNullableMemberAccess
import com.itsaky.androidide.lsp.kotlin.utils.nullSafetyVariants
import com.itsaky.androidide.lsp.models.CodeActionItem
import com.itsaky.androidide.lsp.models.CodeActionKind
import com.itsaky.androidide.lsp.models.Command
import com.itsaky.androidide.lsp.models.DiagnosticItem
import com.itsaky.androidide.lsp.models.DiagnosticsInSelection
import com.itsaky.androidide.lsp.models.DocumentChange
import com.itsaky.androidide.resources.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Offers null-safety quick fixes on an UNSAFE_CALL diagnostic (`receiver.selector` where `receiver`
 * is nullable): assert non-null (`!!`), safe call (`?.`), or an Elvis fallback (`?:`). Each is a
 * separate suggestion. Diagnostic-driven, mirroring [AddImportAction].
 *
 * Scope is deliberately the dot-qualified member-access case (UNSAFE_CALL). The sibling unsafe-call
 * factories (implicit-invoke/infix/operator) sit on other PSI shapes and would need different
 * rewrites; nullable type-mismatch (assignment/return/argument) is a different fix entirely. Both
 * are out of scope here.
 */
class NullSafetyAction : BaseKotlinCodeAction() {
	override var titleTextRes: Int = R.string.action_null_safety_fixes
	override val id: String = "ide.editor.lsp.kt.diagnostics.nullSafety"
	override var label: String = ""

	/**
	 * The first null-safety-fixable diagnostic in the selection. Prefers [DiagnosticsInSelection]
	 * (any matching diagnostic in the selected region); falls back to the at-selection-start
	 * [DiagnosticItem] when no container is present.
	 */
	private fun ActionData.nullSafetyDiagnostic(): DiagnosticItem? {
		val predicate = { d: DiagnosticItem ->
			(d.extra as? KotlinDiagnosticExtra)?.action == DiagnosticAction.NullSafetyFix
		}
		get<DiagnosticsInSelection>()?.let { return it.diagnostics.firstOrNull(predicate) }
		return get<DiagnosticItem>()?.takeIf(predicate)
	}

	override fun prepare(data: ActionData) {
		super.prepare(data)

		if (!visible || data.nullSafetyDiagnostic() == null) {
			markInvisible()
			return
		}
	}

	override suspend fun execAction(data: ActionData): List<NullSafetyVariant> =
		runCatching {
			val diagnostic = data.nullSafetyDiagnostic() ?: return emptyList()
			val extra = diagnostic.extra as? KotlinDiagnosticExtra ?: return emptyList()

			val nioPath = data.requireFile().toPath()

			// Fetch the live KtFile BEFORE entering `read` (deadlock rule: its refresh needs write access).
			val ktFile =
				withContext(Dispatchers.IO) {
					extra.compilationEnv.ktSymbolIndex
						.getCurrentKtFile(nioPath)
						.get()
				} ?: return emptyList()

			extra.compilationEnv.project.read {
				val qe =
					findNullableMemberAccess(
						ktFile,
						diagnostic.range.start.requireIndex(),
						diagnostic.range.end.requireIndex(),
					) ?: return@read emptyList()
				nullSafetyVariants(qe)
			}
		}.getOrElse { e ->
			logger.warn("Failed to compute null-safety fixes", e)
			emptyList()
		}

	override fun postExec(
		data: ActionData,
		result: Any,
	) {
		super.postExec(data, result)
		if (result !is List<*> || result.isEmpty()) return

		@Suppress("UNCHECKED_CAST")
		result as List<NullSafetyVariant>

		val client =
			data.languageClient ?: run {
				logger.warn("No language client set. Cannot apply null-safety fix.")
				return
			}
		val context = data.requireContext()
		val nioPath = data.requireFile().toPath()

		val actions =
			result.map { variant ->
				CodeActionItem(
					title = context.getString(variant.kind.titleRes),
					changes = listOf(DocumentChange(file = nioPath, edits = variant.edits)),
					kind = CodeActionKind.QuickFix,
					command = Command("", ""), // no post-action command (edits are already final)
				)
			}

		when (actions.size) {
			0 -> {
				return
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
							?: logger.error("Index $which is out of bounds for actions of size ${actions.size}")
					}.show()
			}
		}
	}
}

private val NullSafetyKind.titleRes: Int
	get() =
		when (this) {
			NullSafetyKind.ASSERT_NON_NULL -> R.string.action_null_safety_assert
			NullSafetyKind.SAFE_CALL -> R.string.action_null_safety_safe_call
			NullSafetyKind.ELVIS -> R.string.action_null_safety_elvis
		}
