package com.itsaky.androidide.lsp.java.actions

import android.content.Context
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.requireContext
import com.itsaky.androidide.actions.requireEditor
import com.itsaky.androidide.actions.requireFile
import com.itsaky.androidide.idetooltips.TooltipTag
import com.itsaky.androidide.lsp.java.refactor.ExtractMethodPlan
import com.itsaky.androidide.lsp.java.refactor.ExtractionRefusal
import com.itsaky.androidide.lsp.java.refactor.JAVA_KEYWORDS
import com.itsaky.androidide.lsp.java.refactor.JAVA_NAME_MESSAGES
import com.itsaky.androidide.lsp.java.refactor.buildExtractMethodPlan
import com.itsaky.androidide.lsp.java.refactor.buildExtractMethodRewrites
import com.itsaky.androidide.lsp.java.refactor.candidateFor
import com.itsaky.androidide.lsp.java.refactor.toMethodCandidateViews
import com.itsaky.androidide.lsp.models.CodeActionItem
import com.itsaky.androidide.lsp.models.CodeActionKind
import com.itsaky.androidide.lsp.models.Command
import com.itsaky.androidide.lsp.models.DocumentChange
import com.itsaky.androidide.lsp.refactor.toTextEdit
import com.itsaky.androidide.lsp.ui.ExtractMethodSelection
import com.itsaky.androidide.lsp.ui.ExtractMethodSheet
import com.itsaky.androidide.lsp.ui.findFragmentActivity
import com.itsaky.androidide.projects.FileManager
import com.itsaky.androidide.resources.R
import com.itsaky.androidide.utils.flashError
import com.itsaky.androidide.utils.flashInfo
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException

/**
 * Moves the expression at the cursor, or a selected range of statements, into a new `private` method.
 *
 * [execAction] runs one attributed compile and returns a plain-data [ExtractMethodPlan]; [postExec]
 * shows the shared sheet and turns the user's selection into two text edits with pure offset
 * arithmetic. Where the region cannot be moved faithfully the plan carries a typed refusal, which
 * postExec renders as a specific message rather than a generic failure (ADR 0014).
 */
class ExtractMethodAction : BaseJavaCodeAction() {
	companion object {
		const val ID = "ide.editor.lsp.java.extractMethod"

		private val log = LoggerFactory.getLogger(ExtractMethodAction::class.java)
	}

	override val titleTextRes: Int = R.string.action_extract_method
	override var tooltipTag: String = TooltipTag.EDITOR_CODE_ACTIONS_EXTRACT_METHOD

	override val id: String = ID
	override var label: String = ""

	// Deciding whether anything is extractable needs an attributed compile, far too costly for
	// prepare() on the UI thread. BaseJavaCodeAction's file-type and module gate is all that applies;
	// the action stays visible on any Java file and reports a refusal instead.
	override var requiresUIThread: Boolean = false

	override suspend fun execAction(data: ActionData): ExtractMethodPlan {
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
				buildExtractMethodPlan(task, file, selectionStart, selectionEnd, version)
			}
		}.getOrElse { error ->
			if (error is CancellationException) throw error
			log.warn("Could not analyse {} for extract method.", file, error)
			ExtractMethodPlan.refused(ExtractionRefusal.CouldNotAnalyse, documentVersion = version)
		}
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
					log.warn("No FragmentActivity for the editor context. Cannot show the extract sheet.")
					flashError(R.string.msg_cannot_perform_fix)
					return
				}

		val shown =
			ExtractMethodSheet.show(
				activity,
				result.toMethodCandidateViews(),
				JAVA_KEYWORDS,
				JAVA_NAME_MESSAGES,
			) { selection -> applySelection(data, result, selection) }
		if (!shown) {
			log.warn("Fragment manager unavailable. Cannot show the extract sheet.")
		}
	}

	/**
	 * Turns the user's selection into the two edits and hands them to the language client.
	 *
	 * Runs from the sheet's click handler, outside `execAction` and so outside every guard the action
	 * framework provides -- nothing here may throw, hence the [runCatching].
	 */
	private fun applySelection(
		data: ActionData,
		plan: ExtractMethodPlan,
		selection: ExtractMethodSelection,
	) {
		runCatching { performSelection(data, plan, selection) }.onFailure { error ->
			log.error("Failed to apply the extract-method selection '{}'", selection.name, error)
			flashError(R.string.msg_cannot_perform_fix)
		}
	}

	/**
	 * The document version is re-read here rather than trusted from the plan: the editor stays reachable
	 * while the sheet is open, and applying spans computed against older text would corrupt the file.
	 * Refusing is always safe; the user can invoke the action again.
	 */
	private fun performSelection(
		data: ActionData,
		plan: ExtractMethodPlan,
		selection: ExtractMethodSelection,
	) {
		val file = data.requireFile().toPath()
		// A plan built while the document was closed carries no version to compare, so there is nothing
		// to prove the text still matches: refuse rather than apply spans on trust.
		if (plan.documentVersion == null || documentVersionOf(file) != plan.documentVersion) {
			flashInfo(R.string.msg_extract_method_file_changed)
			return
		}

		val candidate =
			plan.candidateFor(selection) ?: run {
				log.warn("Selection {} does not address the plan it came from.", selection)
				flashError(R.string.msg_cannot_perform_fix)
				return
			}

		val rewrites =
			buildExtractMethodRewrites(plan.fileText, candidate, selection.name) ?: run {
				log.warn("Could not build an extract-method rewrite for '{}'", candidate.label)
				flashError(R.string.msg_cannot_perform_fix)
				return
			}

		val client =
			data.getLanguageClient() ?: run {
				log.warn("No language client set. Cannot extract method.")
				return
			}

		client.performCodeAction(
			CodeActionItem(
				title = label,
				changes =
					listOf(
						DocumentChange(
							file = file,
							// Already in descending document order: applyActionEdits applies these in list
							// order with line/column ranges, so the call site must not shift the insertion point.
							edits = rewrites.map { it.toTextEdit(plan.fileText) },
						),
					),
				kind = CodeActionKind.QuickFix,
				// The rewrites are emitted fully indented. Running google-java-format here would reformat
				// the whole file into the same undo step as the extraction.
				command = Command("", ""),
			),
		)
	}

	/**
	 * Each refusal names the construct in the way; a generic message reads as a broken feature.
	 *
	 * Exhaustive with no `else`: a future variant added without a message here is a compile error rather
	 * than a silent gap.
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

			is ExtractionRefusal.ReassignsOuterVar -> {
				context.getString(R.string.msg_extract_method_reassigns_outer_var, refusal.name)
			}

			ExtractionRefusal.ExitsRegion -> {
				context.getString(R.string.msg_extract_method_exits_region)
			}

			is ExtractionRefusal.UsesTypeParameter -> {
				context.getString(R.string.msg_extract_method_uses_type_parameter, refusal.name)
			}

			ExtractionRefusal.UnrenderableType -> {
				context.getString(R.string.msg_extract_method_unrenderable_type)
			}

			is ExtractionRefusal.CapturedLocalDeclaration -> {
				context.getString(R.string.msg_extract_method_captured_local_declaration, refusal.name)
			}
		}

	/** Null when the document is not open, which the confirm guard treats as unverifiable and refuses. */
	private fun documentVersionOf(path: Path): Int? = FileManager.getActiveDocument(path)?.version
}
