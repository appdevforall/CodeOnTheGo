package com.itsaky.androidide.lsp.kotlin.refactor.ui

import com.itsaky.androidide.lsp.kotlin.utils.refactor.CandidateExpression
import com.itsaky.androidide.lsp.kotlin.utils.refactor.NameProblem
import com.itsaky.androidide.lsp.kotlin.utils.refactor.ScopeOption

/**
 * Everything the extract-variable sheet renders, derived entirely from the
 * [com.itsaky.androidide.lsp.kotlin.utils.refactor.ExtractionPlan].
 *
 * [showCandidatePicker] is false when the plan holds a single candidate, or when the user's selection
 * already matched an expression exactly -- in both cases asking which expression they meant would be
 * asking a question they have already answered.
 *
 * [occurrenceCount] counts every site the selected scope would rewrite, **including** the one the user
 * selected, so "Replace all 3 occurrences" means three sites in total. [showReplaceAll] is false at a
 * count of one, where the toggle would have nothing to do.
 */
data class ExtractVariableUiState(
	val candidateLabels: List<String>,
	val selectedCandidate: Int,
	val showCandidatePicker: Boolean,
	val name: String,
	val nameProblem: NameProblem?,
	val scopeLabels: List<String>,
	val selectedScope: Int,
	val occurrenceCount: Int,
	val replaceAll: Boolean,
) {
	val showReplaceAll: Boolean get() = occurrenceCount > 1

	val showScopePicker: Boolean get() = scopeLabels.size > 1

	val canConfirm: Boolean get() = nameProblem == null
}

/** What the sheet reports back up; the ViewModel never touches the document itself. */
sealed interface ExtractVariableUiEvent {
	data class CandidateSelected(
		val index: Int,
	) : ExtractVariableUiEvent

	data class NameChanged(
		val name: String,
	) : ExtractVariableUiEvent

	data class ScopeSelected(
		val index: Int,
	) : ExtractVariableUiEvent

	data class ReplaceAllChanged(
		val replaceAll: Boolean,
	) : ExtractVariableUiEvent

	data object Confirmed : ExtractVariableUiEvent

	data object Dismissed : ExtractVariableUiEvent
}

/**
 * The user's finished decision, handed to the action to turn into an edit.
 *
 * Kept free of offsets and text so the sheet stays a pure chooser: resolving this into a rewrite, and
 * checking the document has not moved on, both belong to the action.
 */
data class ExtractionChoice(
	val candidate: CandidateExpression,
	val scope: ScopeOption,
	val name: String,
	val replaceAll: Boolean,
)
