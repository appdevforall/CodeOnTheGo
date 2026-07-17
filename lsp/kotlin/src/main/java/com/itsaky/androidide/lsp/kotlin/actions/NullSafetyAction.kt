package com.itsaky.androidide.lsp.kotlin.actions

import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.has
import com.itsaky.androidide.actions.markInvisible
import com.itsaky.androidide.actions.newDialogBuilder
import com.itsaky.androidide.actions.require
import com.itsaky.androidide.actions.requireContext
import com.itsaky.androidide.actions.requireFile
import com.itsaky.androidide.lsp.kotlin.compiler.read
import com.itsaky.androidide.lsp.kotlin.diagnostic.KotlinDiagnosticExtra
import com.itsaky.androidide.lsp.kotlin.utils.NullSafetyKind
import com.itsaky.androidide.lsp.kotlin.utils.NullSafetyVariant
import com.itsaky.androidide.lsp.kotlin.utils.findNullableMemberAccess
import com.itsaky.androidide.lsp.kotlin.utils.nullSafetyVariants
import com.itsaky.androidide.lsp.models.CodeActionItem
import com.itsaky.androidide.lsp.models.CodeActionKind
import com.itsaky.androidide.lsp.models.Command
import com.itsaky.androidide.lsp.models.DiagnosticItem
import com.itsaky.androidide.lsp.models.DocumentChange
import com.itsaky.androidide.resources.R
import org.slf4j.LoggerFactory

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

	companion object {
		private val logger = LoggerFactory.getLogger(NullSafetyAction::class.java)
	}

	override fun prepare(data: ActionData) {
		super.prepare(data)

		if (!visible || !data.has<DiagnosticItem>()) {
			markInvisible()
			return
		}

		val extra = data.require<DiagnosticItem>().extra as? KotlinDiagnosticExtra
		if (extra?.nullSafetyFactory == null) {
			markInvisible()
			return
		}
	}

	override suspend fun execAction(data: ActionData): List<NullSafetyVariant> =
		runCatching {
			val diagnostic = data.require<DiagnosticItem>()
			val extra = diagnostic.extra as? KotlinDiagnosticExtra ?: return emptyList()
			if (extra.nullSafetyFactory == null) return emptyList()

			val nioPath = data.requireFile().toPath()
			// Fetch the live KtFile BEFORE entering `read` (deadlock rule: its refresh needs write access).
			val ktFile = extra.compilationEnv.ktSymbolIndex.getCurrentKtFile(nioPath).get() ?: return emptyList()

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
			0 -> return
			1 -> client.performCodeAction(actions[0])
			else ->
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

private val NullSafetyKind.titleRes: Int
	get() =
		when (this) {
			NullSafetyKind.ASSERT_NON_NULL -> R.string.action_null_safety_assert
			NullSafetyKind.SAFE_CALL -> R.string.action_null_safety_safe_call
			NullSafetyKind.ELVIS -> R.string.action_null_safety_elvis
		}
