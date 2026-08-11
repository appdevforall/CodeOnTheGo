package com.itsaky.androidide.lsp.kotlin.actions

import android.content.Context
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.get
import com.itsaky.androidide.actions.requireContext
import com.itsaky.androidide.actions.requireEditor
import com.itsaky.androidide.actions.requireFile
import com.itsaky.androidide.idetooltips.TooltipTag
import com.itsaky.androidide.lsp.kotlin.KotlinLanguageServer
import com.itsaky.androidide.lsp.kotlin.compiler.modules.ScheduledCancelChecker
import com.itsaky.androidide.lsp.kotlin.refactor.ui.ExtractMethodChoice
import com.itsaky.androidide.lsp.kotlin.refactor.ui.ExtractMethodSheet
import com.itsaky.androidide.lsp.kotlin.refactor.ui.findFragmentActivity
import com.itsaky.androidide.lsp.kotlin.utils.refactor.ExtractMethodPlan
import com.itsaky.androidide.lsp.kotlin.utils.refactor.ExtractionRefusal
import com.itsaky.androidide.lsp.kotlin.utils.refactor.buildExtractMethodPlan
import com.itsaky.androidide.lsp.kotlin.utils.refactor.buildExtractMethodRewrites
import com.itsaky.androidide.lsp.kotlin.utils.refactor.toTextEdit
import com.itsaky.androidide.lsp.models.CodeActionItem
import com.itsaky.androidide.lsp.models.CodeActionKind
import com.itsaky.androidide.lsp.models.Command
import com.itsaky.androidide.lsp.models.DocumentChange
import com.itsaky.androidide.projects.FileManager
import com.itsaky.androidide.resources.R
import com.itsaky.androidide.tasks.createJobCancelChecker
import com.itsaky.androidide.utils.flashError
import com.itsaky.androidide.utils.flashInfo
import java.nio.file.Path

/**
 * Moves the expression at the cursor, or a selected range of statements, into a new `private fun`.
 *
 * [execAction] runs one background analysis pass and returns a plain-data [ExtractMethodPlan];
 * [postExec] shows the sheet and turns the user's choice into two text edits with pure offset
 * arithmetic. Where the region cannot be moved faithfully the plan carries a typed refusal, which
 * postExec renders as a specific message rather than a generic failure (ADR 0013).
 */
class ExtractMethodAction : BaseKotlinCodeAction() {
	companion object {
		const val ID = "ide.editor.lsp.kt.extractMethod"
	}

	override var titleTextRes: Int = R.string.action_extract_method
	override var tooltipTag: String = TooltipTag.EDITOR_CODE_ACTIONS_KT_EXTRACT_METHOD

	override val id: String = ID
	override var label: String = ""

	// Analysis must not run on the UI thread, so the selection is read at the top of execAction on a
	// background thread. A torn read while the user is mid-edit can only produce a plan the
	// document-version guard then refuses to apply.
	override var requiresUIThread: Boolean = false

	// Intentionally no prepare() visibility gate: deciding whether anything is extractable needs a K2
	// analysis session, far too costly for prepare(). The action stays visible on any Kotlin file and
	// reports a refusal instead.

	override suspend fun execAction(data: ActionData): ExtractMethodPlan {
		val server =
			data.get<KotlinLanguageServer>()
				?: return ExtractMethodPlan.refused(ExtractionRefusal.CouldNotAnalyse)
		val nioPath = data.requireFile().toPath()
		val env =
			server.compilationEnvironmentFor(nioPath)
				?: return ExtractMethodPlan.refused(ExtractionRefusal.CouldNotAnalyse)

		val cursor = data.requireEditor().cursor
		return buildExtractMethodPlan(
			env = env,
			nioPath = nioPath,
			selectionStart = minOf(cursor.left, cursor.right),
			selectionEnd = maxOf(cursor.left, cursor.right),
			documentVersion = documentVersionOf(nioPath),
			// Ties the analysis to this action's coroutine: cancelling the action aborts the analysis.
			cancelChecker = ScheduledCancelChecker(createJobCancelChecker()),
		)
	}

	override fun postExec(
		data: ActionData,
		result: Any,
	) {
		super.postExec(data, result)
		if (result !is ExtractMethodPlan) return

		val context = data.requireContext()
		if (result.isEmpty) {
			flashInfo(refusalMessage(context, result.refusal ?: ExtractionRefusal.CouldNotAnalyse))
			return
		}

		val activity =
			context.findFragmentActivity()
				?: run {
					// A wiring problem rather than a user path: the editor is always hosted by one.
					logger.warn("No FragmentActivity for the editor context. Cannot show the extract sheet.")
					flashError(R.string.msg_cannot_perform_fix)
					return
				}

		val shown = ExtractMethodSheet.show(activity, result) { choice -> applyChoice(data, result, choice) }
		if (!shown) {
			logger.warn("Fragment manager unavailable. Cannot show the extract sheet.")
		}
	}

	/**
	 * Turns the user's choice into the two edits and hands them to the language client.
	 *
	 * The document version is re-read here rather than trusted from the plan: the editor stays
	 * reachable while the sheet is open, and applying spans computed against older text would corrupt
	 * the file. Refusing is always safe; the user can invoke the action again.
	 *
	 * Runs from the sheet's click handler, outside `execAction` and so outside every guard the action
	 * framework provides -- nothing here may throw (R16), hence the [runCatching].
	 */
	private fun applyChoice(
		data: ActionData,
		plan: ExtractMethodPlan,
		choice: ExtractMethodChoice,
	) {
		runCatching { performChoice(data, plan, choice) }.onFailure { error ->
			logger.error("Failed to apply the extract-method choice '{}'", choice.name, error)
			flashError(R.string.msg_cannot_perform_fix)
		}
	}

	private fun performChoice(
		data: ActionData,
		plan: ExtractMethodPlan,
		choice: ExtractMethodChoice,
	) {
		val file = data.requireFile()
		val nioPath = file.toPath()
		if (documentVersionOf(nioPath) != plan.documentVersion) {
			flashInfo(R.string.msg_extract_method_file_changed)
			return
		}

		val rewrites =
			buildExtractMethodRewrites(plan.fileText, choice.candidate, choice.name) ?: run {
				logger.warn("Could not build an extract-method rewrite for '{}'", choice.candidate.label)
				flashError(R.string.msg_cannot_perform_fix)
				return
			}

		val client =
			data.languageClient ?: run {
				logger.warn("No language client set. Cannot extract method.")
				return
			}

		client.performCodeAction(
			CodeActionItem(
				title = label,
				changes =
					listOf(
						DocumentChange(
							file = nioPath,
							// Already in descending document order: applyActionEdits applies these in list
							// order with line/column ranges, so the call site must not shift the insertion point.
							edits = rewrites.map { it.toTextEdit(plan.fileText) },
						),
					),
				kind = CodeActionKind.QuickFix,
				// The rewrites are emitted fully indented; CMD_FORMAT_CODE is a no-op for Kotlin anyway.
				command = Command("", ""),
			),
		)
	}

	/**
	 * Each refusal names the construct in the way; a generic message reads as a broken feature.
	 *
	 * Exhaustive with no `else`: a future variant added without a message here is a compile error
	 * rather than a silent gap.
	 */
	private fun refusalMessage(
		context: Context,
		refusal: ExtractionRefusal,
	): String =
		when (refusal) {
			ExtractionRefusal.NotASingleRegion -> {
				context.getString(R.string.msg_extract_method_not_single_region)
			}

			ExtractionRefusal.CouldNotAnalyse -> {
				context.getString(R.string.msg_extract_method_could_not_analyse)
			}

			is ExtractionRefusal.MultipleOutputs -> {
				context.getString(R.string.msg_extract_method_multiple_outputs, refusal.names.joinToString(", "))
			}

			is ExtractionRefusal.OutputNotReturnable -> {
				context.getString(R.string.msg_extract_method_output_not_returnable, refusal.name)
			}

			is ExtractionRefusal.ReassignsOuterVar -> {
				context.getString(R.string.msg_extract_method_reassigns_outer_var, refusal.name)
			}

			ExtractionRefusal.ExitsRegion -> {
				context.getString(R.string.msg_extract_method_exits_region)
			}

			is ExtractionRefusal.InnerImplicitReceiver -> {
				context.getString(R.string.msg_extract_method_inner_implicit_receiver, refusal.construct)
			}

			is ExtractionRefusal.UsesTypeParameter -> {
				context.getString(R.string.msg_extract_method_uses_type_parameter, refusal.name)
			}

			ExtractionRefusal.UnrenderableType -> {
				context.getString(R.string.msg_extract_method_unrenderable_type)
			}

			ExtractionRefusal.UsesBackingField -> {
				context.getString(R.string.msg_extract_method_uses_backing_field)
			}

			is ExtractionRefusal.SmartCastParameter -> {
				context.getString(R.string.msg_extract_method_smart_cast_parameter, refusal.name)
			}

			is ExtractionRefusal.CapturedLocalDeclaration -> {
				context.getString(R.string.msg_extract_method_captured_local_declaration, refusal.name)
			}
		}

	/** -1 when the document is not open, which never matches a real version and so fails the guard. */
	private fun documentVersionOf(path: Path): Int = FileManager.getActiveDocument(path)?.version ?: -1
}
