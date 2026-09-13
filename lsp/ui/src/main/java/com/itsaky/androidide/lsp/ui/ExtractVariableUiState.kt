package com.itsaky.androidide.lsp.ui

/**
 * Everything the extract-variable sheet renders, derived entirely from the [CandidateView]s it was
 * shown with.
 *
 * [showCandidatePicker] is false only when there is a single candidate. It stays visible for an exact
 * selection: long-press is the natural gesture and selects exactly one token, so hiding the list there
 * leaves no way to widen to an enclosing expression short of cancelling and re-selecting.
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
