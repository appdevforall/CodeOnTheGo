package com.itsaky.androidide.lsp.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Derives the sheet's state from the [MethodCandidateView]s it was given and nothing else -- no
 * analysis, no syntax tree, no I/O -- which is what lets it hold all the sheet's logic and still be a
 * plain unit test, and what lets both language servers share it without either depending on the other.
 *
 * A plain [ViewModelProvider.Factory] rather than a Koin definition, for the same reason as
 * [ExtractVariableViewModel]: sheet-scoped, injects nothing, takes its inputs as runtime arguments.
 */
class ExtractMethodViewModel(
	private val candidates: List<MethodCandidateView>,
	private val keywords: Set<String>,
) : ViewModel() {
	private val _uiState = MutableStateFlow(stateFor(candidateIndex = 0, name = null))
	val uiState: StateFlow<ExtractMethodUiState> = _uiState.asStateFlow()

	fun onEvent(event: ExtractMethodUiEvent) {
		val current = _uiState.value
		when (event) {
			is ExtractMethodUiEvent.CandidateSelected -> {
				if (event.index == current.selectedCandidate) return
				// A different region means a different signature and suggested name, so the name is
				// re-suggested rather than carried over -- the old one described the old region.
				_uiState.value = stateFor(event.index, name = null)
			}

			is ExtractMethodUiEvent.NameChanged -> {
				_uiState.value = stateFor(current.selectedCandidate, name = event.name)
			}

			ExtractMethodUiEvent.Confirmed, ExtractMethodUiEvent.Dismissed -> {
				Unit
			}
		}
	}

	/** The user's decision, or null when the name is unusable. */
	fun selection(): ExtractMethodSelection? {
		val state = _uiState.value
		if (!state.canConfirm) return null
		return ExtractMethodSelection(state.selectedCandidate, state.name)
	}

	private fun candidate(index: Int) = candidates[index.coerceIn(candidates.indices)]

	private fun stateFor(
		candidateIndex: Int,
		name: String?,
	): ExtractMethodUiState {
		val bounded = candidateIndex.coerceIn(candidates.indices)
		val candidate = candidate(bounded)
		val resolvedName = name ?: candidate.suggestedName

		return ExtractMethodUiState(
			candidateLabels = candidates.map { it.label },
			selectedCandidate = bounded,
			showCandidatePicker = candidates.size > 1,
			name = resolvedName,
			nameProblem = validateVariableName(resolvedName, candidate.takenNames, keywords),
			// The same composition the edit builder makes, so the preview cannot drift from the
			// declaration.
			signaturePreview = candidate.signatureFor(resolvedName),
		)
	}

	companion object {
		fun factory(
			candidates: List<MethodCandidateView>,
			keywords: Set<String>,
		): ViewModelProvider.Factory =
			object : ViewModelProvider.Factory {
				@Suppress("UNCHECKED_CAST")
				override fun <T : ViewModel> create(modelClass: Class<T>): T = ExtractMethodViewModel(candidates, keywords) as T
			}
	}
}
