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
import com.itsaky.androidide.lsp.kotlin.refactor.ui.InlineVariableSheet
import com.itsaky.androidide.lsp.kotlin.refactor.ui.findFragmentActivity
import com.itsaky.androidide.lsp.kotlin.utils.refactor.InlineMode
import com.itsaky.androidide.lsp.kotlin.utils.refactor.InlineRefusal
import com.itsaky.androidide.lsp.kotlin.utils.refactor.InlineReport
import com.itsaky.androidide.lsp.kotlin.utils.refactor.InlineVariablePlan
import com.itsaky.androidide.lsp.kotlin.utils.refactor.buildInlineVariablePlan
import com.itsaky.androidide.lsp.kotlin.utils.refactor.buildInlineVariableRewrites
import com.itsaky.androidide.lsp.kotlin.utils.refactor.reportFor
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
 * Replaces the references to the local variable at the cursor with its initializer, and removes the
 * declaration once nothing needs it.
 *
 * [execAction] runs one background analysis pass and returns a plain-data [InlineVariablePlan];
 * [postExec] either applies it immediately or, where the mode table leaves a decision, shows the
 * sheet. Where a reference or the whole inline cannot be performed faithfully the plan carries a
 * typed refusal, which postExec renders as a specific message rather than a generic failure.
 */
class InlineVariableAction : BaseKotlinCodeAction() {
	companion object {
		/** This action's registration id. */
		const val ID = "ide.editor.lsp.kt.inlineVariable"
	}

	override var titleTextRes: Int = R.string.action_inline_variable
	override var tooltipTag: String = TooltipTag.EDITOR_CODE_ACTIONS_KT_INLINE_VARIABLE

	override val id: String = ID
	override var label: String = ""

	/* Analysis must not run on the UI thread, so the cursor is read at the top of execAction on a
	background thread. A torn read while the user is mid-edit can only produce a plan the
	document-version guard then refuses to apply. */
	override var requiresUIThread: Boolean = false

	/* Intentionally no prepare() visibility gate: deciding whether the cursor is on an inlinable local
	needs a K2 analysis session, far too costly for prepare(). The action stays visible on any Kotlin
	file and reports a refusal instead. */

	override suspend fun execAction(data: ActionData): InlineVariablePlan {
		val server =
			data.get<KotlinLanguageServer>()
				?: return InlineVariablePlan.refused(InlineRefusal.CouldNotAnalyse)
		val nioPath = data.requireFile().toPath()
		val env =
			server.compilationEnvironmentFor(nioPath)
				?: return InlineVariablePlan.refused(InlineRefusal.CouldNotAnalyse)

		val cursor = data.requireEditor().cursor
		return buildInlineVariablePlan(
			env = env,
			nioPath = nioPath,
			// The selection start: a user who selected the whole name still points at its first character.
			offset = minOf(cursor.left, cursor.right),
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
		if (result !is InlineVariablePlan) return

		val context = data.requireContext()
		val refusal = result.refusal ?: if (result.modes.isEmpty()) InlineRefusal.CouldNotAnalyse else null
		if (refusal != null) {
			flashInfo(refusalMessage(context, refusal))
			return
		}

		// Only a two-mode choice is a decision; every other path applies immediately.
		if (!result.offersChoice) {
			applyMode(data, result, result.modes.single())
			return
		}

		val activity =
			context.findFragmentActivity()
				?: run {
					// A wiring problem rather than a user path: the editor is always hosted by one.
					logger.warn("No FragmentActivity for the editor context. Cannot show the inline sheet.")
					flashError(R.string.msg_cannot_perform_fix)
					return
				}

		val shown = InlineVariableSheet.show(activity, result) { mode -> applyMode(data, result, mode) }
		if (!shown) {
			logger.warn("Fragment manager unavailable. Cannot show the inline sheet.")
			flashError(R.string.msg_cannot_perform_fix)
		}
	}

	/**
	 * Turns the chosen mode into edits and hands them to the language client.
	 *
	 * Runs from the sheet's click handler on the choice path, outside `execAction` and so outside every
	 * guard the action framework provides -- nothing here may throw, hence the [runCatching].
	 */
	private fun applyMode(
		data: ActionData,
		plan: InlineVariablePlan,
		mode: InlineMode,
	) {
		runCatching { performMode(data, plan, mode) }.onFailure { error ->
			logger.error("Failed to apply the inline-variable mode '{}'", mode.name, error)
			flashError(R.string.msg_cannot_perform_fix)
		}
	}

	private fun performMode(
		data: ActionData,
		plan: InlineVariablePlan,
		mode: InlineMode,
	) {
		val context = data.requireContext()
		val nioPath = data.requireFile().toPath()
		// Re-read rather than trust the plan: the editor stays reachable while the sheet is open, and
		// applying spans computed against older text would corrupt the file.
		if (documentVersionOf(nioPath) != plan.documentVersion) {
			flashInfo(refusalMessage(context, InlineRefusal.FileChanged))
			return
		}

		val rewrites =
			buildInlineVariableRewrites(plan, mode) ?: run {
				logger.warn("Could not build an inline-variable rewrite for '{}'", plan.variableName)
				flashError(R.string.msg_cannot_perform_fix)
				return
			}

		val client =
			data.languageClient ?: run {
				logger.warn("No language client set. Cannot inline variable.")
				flashError(R.string.msg_cannot_perform_fix)
				return
			}

		client.performCodeAction(
			CodeActionItem(
				title = label,
				changes =
					listOf(
						DocumentChange(
							file = nioPath,
							// Already in descending document order: applyActionEdits applies these in list order
							// with line/column ranges, so an earlier edit must never shift a later one.
							edits = rewrites.map { it.toTextEdit(plan.fileText) },
						),
					),
				kind = CodeActionKind.QuickFix,
				// The substitutions are emitted final; CMD_FORMAT_CODE is a no-op for Kotlin anyway.
				command = Command("", ""),
			),
		)

		flashInfo(reportMessage(context, plan.reportFor(mode)))
	}

	/**
	 * Each refusal names what is in the way; a generic message reads as a broken feature.
	 *
	 * Exhaustive with no `else`: a future variant added without a message here is a compile error
	 * rather than a silent gap.
	 */
	private fun refusalMessage(
		context: Context,
		refusal: InlineRefusal,
	): String =
		when (refusal) {
			InlineRefusal.NotAVariable -> {
				context.getString(R.string.msg_inline_variable_not_a_variable)
			}

			InlineRefusal.NotALocalVariable -> {
				context.getString(R.string.msg_inline_variable_not_local)
			}

			is InlineRefusal.NoInitializer -> {
				context.getString(R.string.msg_inline_variable_no_initializer, refusal.name)
			}

			InlineRefusal.DestructuringDeclaration -> {
				context.getString(R.string.msg_inline_variable_destructuring)
			}

			is InlineRefusal.DeclaredTypeIsLoadBearing -> {
				context.getString(R.string.msg_inline_variable_declared_type, refusal.name, refusal.typeText)
			}

			is InlineRefusal.NeverUsed -> {
				context.getString(R.string.msg_inline_variable_never_used, refusal.name)
			}

			is InlineRefusal.NothingInlinable -> {
				context.getString(R.string.msg_inline_variable_nothing_inlinable, refusal.name)
			}

			is InlineRefusal.ReferenceNotInlinable -> {
				context.getString(R.string.msg_inline_variable_reference_not_inlinable, refusal.name)
			}

			InlineRefusal.CouldNotAnalyse -> {
				context.getString(R.string.msg_inline_variable_could_not_analyse)
			}

			InlineRefusal.FileChanged -> {
				context.getString(R.string.msg_inline_variable_file_changed)
			}
		}

	/**
	 * The partial form must say both counts and that the declaration was kept: a user who asked to
	 * inline everything and got three of five needs to know from the flash rather than by rereading the
	 * file.
	 */
	private fun reportMessage(
		context: Context,
		report: InlineReport,
	): String =
		when (report) {
			is InlineReport.InlinedAndRemoved -> {
				context.resources.getQuantityString(
					R.plurals.msg_inline_variable_inlined_all,
					report.count,
					report.count,
					report.name,
				)
			}

			is InlineReport.InlinedKeepingDeclaration -> {
				context.resources.getQuantityString(
					R.plurals.msg_inline_variable_inlined_keeping,
					report.count,
					report.count,
					report.name,
				)
			}

			is InlineReport.InlinedPartially -> {
				context.getString(
					R.string.msg_inline_variable_inlined_partially,
					report.count,
					report.total,
					report.name,
				)
			}
		}

	/** -1 when the document is not open, which never matches a real version and so fails the guard. */
	private fun documentVersionOf(path: Path): Int = FileManager.getActiveDocument(path)?.version ?: -1
}
