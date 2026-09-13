package com.itsaky.androidide.lsp.ui

/**
 * Everything the extract-method sheet renders.
 *
 * There is no scope chooser (the new method is always a sibling of the region's own declaration) and
 * no replace-all checkbox (the region is the only site rewritten), so the sheet is a chooser, a name
 * field and a preview.
 *
 * [signaturePreview] is the signature exactly as it will be emitted -- the one derived artefact, and
 * the one place the derivation can surprise the user. The body is the code they selected and can see
 * behind the sheet, so previewing it says nothing new.
 */
data class ExtractMethodUiState(
	val candidateLabels: List<String>,
	val selectedCandidate: Int,
	val showCandidatePicker: Boolean,
	val name: String,
	val nameProblem: NameProblem?,
	val signaturePreview: String,
) {
	val canConfirm: Boolean get() = nameProblem == null
}

/** What the sheet reports back up; the ViewModel never touches the document itself. */
sealed interface ExtractMethodUiEvent {
	data class CandidateSelected(
		val index: Int,
	) : ExtractMethodUiEvent

	data class NameChanged(
		val name: String,
	) : ExtractMethodUiEvent

	data object Confirmed : ExtractMethodUiEvent

	data object Dismissed : ExtractMethodUiEvent
}
