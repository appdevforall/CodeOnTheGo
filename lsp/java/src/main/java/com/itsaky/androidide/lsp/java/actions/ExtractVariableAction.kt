package com.itsaky.androidide.lsp.java.actions

import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.requireContext
import com.itsaky.androidide.actions.requireEditor
import com.itsaky.androidide.actions.requireFile
import com.itsaky.androidide.idetooltips.TooltipTag
import com.itsaky.androidide.lsp.java.refactor.ExtractionPlan
import com.itsaky.androidide.lsp.java.refactor.JAVA_KEYWORDS
import com.itsaky.androidide.lsp.java.refactor.JAVA_NAME_MESSAGES
import com.itsaky.androidide.lsp.java.refactor.buildExtractVariableRewrite
import com.itsaky.androidide.lsp.java.refactor.buildExtractionPlan
import com.itsaky.androidide.lsp.java.refactor.candidateAndScopeFor
import com.itsaky.androidide.lsp.java.refactor.toCandidateViews
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
import com.itsaky.androidide.utils.flashError
import com.itsaky.androidide.utils.flashInfo
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException

/**
 * Extracts the expression at the cursor, or the selected one, into a local variable.
 *
 * The work is split so nothing heavy touches the UI thread: [execAction] runs one attributed compile
 * and returns a plain-data [ExtractionPlan] covering every candidate, then [postExec] shows the shared
 * sheet and turns the user's selection into a single text edit with pure offset arithmetic.
 */
class ExtractVariableAction : BaseJavaCodeAction() {
	companion object {
		const val ID = "ide.editor.lsp.java.extractVariable"

		private val log = LoggerFactory.getLogger(ExtractVariableAction::class.java)
	}

	override val titleTextRes: Int = R.string.action_extract_variable
	override var tooltipTag: String = TooltipTag.EDITOR_CODE_ACTIONS_EXTRACT_VARIABLE

	override val id: String = ID
	override var label: String = ""

	// Deciding whether anything is extractable needs an attributed compile, far too costly for
	// prepare() on the UI thread. BaseJavaCodeAction's file-type and module gate is all that applies;
	// the action stays visible on any Java file and reports "nothing to extract" instead, as
	// OrganizeImportsAction does.
	override var requiresUIThread: Boolean = false

	override suspend fun execAction(data: ActionData): ExtractionPlan {
		val file = data.requireFile().toPath()
		val cursor = data.requireEditor().cursor
		val selectionStart = minOf(cursor.left, cursor.right)
		val selectionEnd = maxOf(cursor.left, cursor.right)
		val version = documentVersionOf(file)

		// Resolving the compiler and taking its lock can both throw, and neither is inside the planner's
		// own guard. DefaultActionsRegistry catches only IllegalArgumentException and this runs on a scope
		// with no exception handler, so anything else would crash the app rather than fail the action.
		return runCatching {
			data.requireCompiler().compile(file).get { task ->
				buildExtractionPlan(task, file, selectionStart, selectionEnd, version)
			}
		}.getOrElse { error ->
			if (error is CancellationException) throw error
			log.warn("Could not analyse {} for extract variable.", file, error)
			ExtractionPlan.empty()
		}
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

		val context = data.requireContext()
		val activity =
			context.findFragmentActivity()
				?: run {
					// A wiring problem rather than a user path: the editor is always hosted by one.
					log.warn("No FragmentActivity for the editor context. Cannot show the extract sheet.")
					flashError(R.string.msg_cannot_perform_fix)
					return
				}

		val shown =
			ExtractVariableSheet.show(
				activity,
				result.toCandidateViews(context),
				JAVA_KEYWORDS,
				JAVA_NAME_MESSAGES,
			) { selection -> applySelection(data, result, selection) }
		if (!shown) {
			log.warn("Fragment manager unavailable. Cannot show the extract sheet.")
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
		val file = data.requireFile().toPath()
		// A plan built while the document was closed carries no version to compare, so there is nothing
		// to prove the text still matches: refuse rather than apply spans on trust.
		if (plan.documentVersion == null || documentVersionOf(file) != plan.documentVersion) {
			flashInfo(R.string.msg_extract_variable_file_changed)
			return
		}

		val (candidate, scope) =
			plan.candidateAndScopeFor(selection) ?: run {
				log.warn("Selection {} does not address the plan it came from.", selection)
				flashError(R.string.msg_cannot_perform_fix)
				return
			}

		val rewrite =
			buildExtractVariableRewrite(
				fileText = plan.fileText,
				candidateSpan = candidate.span,
				declaredType = candidate.declaredType,
				scope = scope,
				name = selection.name,
				replaceAll = selection.replaceAll,
			) ?: run {
				log.warn("Could not build an extract-variable rewrite for '{}'", candidate.label)
				flashError(R.string.msg_cannot_perform_fix)
				return
			}

		val client =
			data.getLanguageClient() ?: run {
				log.warn("No language client set. Cannot extract variable.")
				return
			}

		client.performCodeAction(
			CodeActionItem(
				title = label,
				changes = listOf(DocumentChange(file = file, edits = listOf(rewrite.toTextEdit(plan.fileText)))),
				kind = CodeActionKind.QuickFix,
				// The rewrite is emitted fully indented. Running google-java-format here would reformat
				// the whole file into the same undo step as the extraction.
				command = Command("", ""),
			),
		)
	}

	/** Null when the document is not open, which the confirm guard treats as unverifiable and refuses. */
	private fun documentVersionOf(path: Path): Int? = FileManager.getActiveDocument(path)?.version
}
