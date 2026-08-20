package com.itsaky.androidide.lsp.kotlin.refactor.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.itsaky.androidide.lsp.kotlin.utils.refactor.ExtractMethodPlan
import com.itsaky.androidide.lsp.kotlin.utils.refactor.signatureText
import com.itsaky.androidide.lsp.kotlin.utils.refactor.validateVariableName
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Derives the sheet's state from an [ExtractMethodPlan] and nothing else -- no analysis, no PSI, no
 * I/O -- which is what lets it hold all the sheet's logic and still be a plain unit test.
 *
 * A plain [ViewModelProvider.Factory] rather than a Koin definition, for the same reason as
 * `ExtractVariableViewModel`: sheet-scoped, injects nothing, takes the plan as a runtime argument.
 */
class ExtractMethodViewModel(
	private val plan: ExtractMethodPlan,
) : ViewModel() {
	private val _uiState = MutableStateFlow(stateFor(candidateIndex = 0, name = null))
	val uiState: StateFlow<ExtractMethodUiState> = _uiState.asStateFlow()

	fun onEvent(event: ExtractMethodUiEvent) {
		val current = _uiState.value
		when (event) {
			is ExtractMethodUiEvent.CandidateSelected -> {
				if (event.index == current.selectedCandidate) return
				// A different expression means a different signature and suggested name, so the name is
				// re-suggested rather than carried over -- the old one described the old expression.
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
	fun choice(): ExtractMethodChoice? {
		val state = _uiState.value
		if (!state.canConfirm) return null
		return ExtractMethodChoice(candidate(state.selectedCandidate), state.name)
	}

	private fun candidate(index: Int) = plan.candidates[index.coerceIn(plan.candidates.indices)]

	private fun stateFor(
		candidateIndex: Int,
		name: String?,
	): ExtractMethodUiState {
		val bounded = candidateIndex.coerceIn(plan.candidates.indices)
		val candidate = candidate(bounded)
		val resolvedName = name ?: candidate.suggestedName

		return ExtractMethodUiState(
			candidateLabels = plan.candidates.map { it.label },
			selectedCandidate = bounded,
			showCandidatePicker = plan.candidates.size > 1,
			name = resolvedName,
			nameProblem = validateVariableName(resolvedName, candidate.takenNames),
			// The same call the edit builder makes, so the preview cannot drift from the declaration.
			signaturePreview = candidate.signatureText(resolvedName),
		)
	}

	companion object {
		fun factory(plan: ExtractMethodPlan): ViewModelProvider.Factory =
			object : ViewModelProvider.Factory {
				@Suppress("UNCHECKED_CAST")
				override fun <T : ViewModel> create(modelClass: Class<T>): T = ExtractMethodViewModel(plan) as T
			}
	}
}
