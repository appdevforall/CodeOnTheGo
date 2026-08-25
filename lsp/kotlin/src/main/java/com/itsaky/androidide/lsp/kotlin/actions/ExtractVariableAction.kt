package com.itsaky.androidide.lsp.kotlin.actions

import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.get
import com.itsaky.androidide.actions.requireContext
import com.itsaky.androidide.actions.requireEditor
import com.itsaky.androidide.actions.requireFile
import com.itsaky.androidide.idetooltips.TooltipTag
import com.itsaky.androidide.lsp.kotlin.KotlinLanguageServer
import com.itsaky.androidide.lsp.kotlin.compiler.modules.ScheduledCancelChecker
import com.itsaky.androidide.lsp.kotlin.refactor.KOTLIN_NAME_MESSAGES
import com.itsaky.androidide.lsp.kotlin.refactor.candidateAndScopeFor
import com.itsaky.androidide.lsp.kotlin.refactor.toCandidateViews
import com.itsaky.androidide.lsp.kotlin.utils.refactor.ExtractionPlan
import com.itsaky.androidide.lsp.kotlin.utils.refactor.HARD_KEYWORDS
import com.itsaky.androidide.lsp.kotlin.utils.refactor.buildExtractVariableRewrite
import com.itsaky.androidide.lsp.kotlin.utils.refactor.buildExtractionPlan
import com.itsaky.androidide.lsp.models.CodeActionItem
import com.itsaky.androidide.lsp.models.CodeActionKind
import com.itsaky.androidide.lsp.models.Command
import com.itsaky.androidide.lsp.models.DocumentChange
import com.itsaky.androidide.lsp.refactor.toTextEdit
import com.itsaky.androidide.lsp.ui.ExtractVariableSelection
import com.itsaky.androidide.lsp.ui.ExtractVariableSheet
import com.itsaky.androidide.lsp.ui.findFragmentActivity
import com.itsaky.androidide.projects.FileManager
import com.itsaky.androidide.resources.R
import com.itsaky.androidide.tasks.createJobCancelChecker
import com.itsaky.androidide.utils.flashError
import com.itsaky.androidide.utils.flashInfo
import java.nio.file.Path

/**
 * Extracts the expression at the cursor, or the selected one, into a local `val`.
 *
 * The work is split so nothing heavy touches the UI thread: [execAction] runs one background analysis
 * pass and returns a plain-data [ExtractionPlan] covering every candidate, then [postExec] shows the
 * sheet and turns the user's choice into a single text edit with pure offset arithmetic.
 */
class ExtractVariableAction : BaseKotlinCodeAction() {
	companion object {
		const val ID = "ide.editor.lsp.kt.extractVariable"
	}

	override var titleTextRes: Int = R.string.action_extract_variable
	override var tooltipTag: String = TooltipTag.EDITOR_CODE_ACTIONS_KT_EXTRACT_VARIABLE

	override val id: String = ID
	override var label: String = ""

	// Analysis must not run on the UI thread. The selection is therefore read at the top of
	// execAction on a background thread, as ImplementMembersAction does; a torn read while the user
	// is mid-edit can only produce a plan the document-version guard then refuses to apply.
	override var requiresUIThread: Boolean = false

	// Intentionally no prepare() visibility gate: deciding whether anything is extractable needs a K2
	// analysis session, far too costly for prepare() (UI thread). The action stays visible on any
	// Kotlin file and reports "nothing to extract" instead. Matches OrganizeImportsAction and
	// ImplementMembersAction.

	override suspend fun execAction(data: ActionData): ExtractionPlan {
		val server = data.get<KotlinLanguageServer>() ?: return ExtractionPlan.empty()
		val nioPath = data.requireFile().toPath()
		val env = server.compilationEnvironmentFor(nioPath) ?: return ExtractionPlan.empty()

		val cursor = data.requireEditor().cursor
		val selectionStart = minOf(cursor.left, cursor.right)
		val selectionEnd = maxOf(cursor.left, cursor.right)

		return buildExtractionPlan(
			env = env,
			nioPath = nioPath,
			selectionStart = selectionStart,
			selectionEnd = selectionEnd,
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
		if (result !is ExtractionPlan) return

		if (result.isEmpty) {
			flashInfo(R.string.msg_extract_variable_nothing_to_extract)
			return
		}

		val activity =
			data.requireContext().findFragmentActivity()
				?: run {
					// A wiring problem rather than a user path: the editor is always hosted by one.
					logger.warn("No FragmentActivity for the editor context. Cannot show the extract sheet.")
					flashError(R.string.msg_cannot_perform_fix)
					return
				}

		val shown =
			ExtractVariableSheet.show(
				activity,
				result.toCandidateViews(),
				HARD_KEYWORDS,
				KOTLIN_NAME_MESSAGES,
			) { selection -> applySelection(data, result, selection) }
		if (!shown) {
			logger.warn("Fragment manager unavailable. Cannot show the extract sheet.")
		}
	}

	/**
	 * Turns the user's selection into one edit and hands it to the language client.
	 *
	 * The document version is re-read here rather than trusted from the plan: the editor stays
	 * reachable while the sheet is open, and applying spans computed against older text would corrupt
	 * the file. Refusing is always safe; the user can invoke the action again.
	 */
	private fun applySelection(
		data: ActionData,
		plan: ExtractionPlan,
		selection: ExtractVariableSelection,
	) {
		val file = data.requireFile()
		val nioPath = file.toPath()
		if (documentVersionOf(nioPath) != plan.documentVersion) {
			flashInfo(R.string.msg_extract_variable_file_changed)
			return
		}

		val (candidate, scope) =
			plan.candidateAndScopeFor(selection) ?: run {
				logger.warn("Selection {} does not address the plan it came from.", selection)
				flashError(R.string.msg_cannot_perform_fix)
				return
			}

		val rewrite =
			buildExtractVariableRewrite(
				fileText = plan.fileText,
				candidateSpan = candidate.span,
				scope = scope,
				name = selection.name,
				replaceAll = selection.replaceAll,
			) ?: run {
				logger.warn("Could not build an extract-variable rewrite for '{}'", candidate.label)
				flashError(R.string.msg_cannot_perform_fix)
				return
			}

		val client =
			data.languageClient ?: run {
				logger.warn("No language client set. Cannot extract variable.")
				return
			}

		client.performCodeAction(
			CodeActionItem(
				title = label,
				changes = listOf(DocumentChange(file = nioPath, edits = listOf(rewrite.toTextEdit(plan.fileText)))),
				kind = CodeActionKind.QuickFix,
				// The rewrite is emitted fully indented; CMD_FORMAT_CODE is a no-op for Kotlin anyway.
				command = Command("", ""),
			),
		)
	}

	/** -1 when the document is not open, which never matches a real version and so fails the guard. */
	private fun documentVersionOf(path: Path): Int = FileManager.getActiveDocument(path)?.version ?: -1
}
