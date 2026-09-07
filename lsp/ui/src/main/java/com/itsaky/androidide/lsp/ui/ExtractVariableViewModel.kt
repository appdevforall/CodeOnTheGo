package com.itsaky.androidide.lsp.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Derives the sheet's state from the [CandidateView]s it was given and nothing else.
 *
 * Each candidate already carries its scope chain and per-scope occurrence count, so switching
 * expression or scope is pure recomputation -- no analysis, no syntax tree, no I/O. That is what lets
 * this class hold all the sheet's logic while remaining a plain unit test, and what lets both language
 * servers share it without either depending on the other.
 *
 * A plain [ViewModelProvider.Factory] rather than a Koin definition (ADR 0006/0009 resolve ViewModels
 * through Koin): this one is sheet-scoped, injects nothing, and takes its inputs as runtime arguments,
 * so a Koin definition would add indirection without providing anything.
 */
class ExtractVariableViewModel(
	private val candidates: List<CandidateView>,
	private val keywords: Set<String>,
) : ViewModel() {
	private val _uiState = MutableStateFlow(initialState())
	val uiState: StateFlow<ExtractVariableUiState> = _uiState.asStateFlow()

	private fun initialState(): ExtractVariableUiState = stateFor(candidateIndex = 0, scopeIndex = 0, replaceAll = false, name = null)

	fun onEvent(event: ExtractVariableUiEvent) {
		val current = _uiState.value
		when (event) {
			is ExtractVariableUiEvent.CandidateSelected -> {
				if (event.index == current.selectedCandidate) return
				// A different expression means a different suggested name, scope chain and count, so the
				// name is re-suggested rather than carried over -- the old one described the old expression.
				_uiState.value = stateFor(event.index, scopeIndex = 0, replaceAll = false, name = null)
			}

			is ExtractVariableUiEvent.ScopeSelected -> {
				if (event.index == current.selectedScope) return
				_uiState.value = stateFor(current.selectedCandidate, event.index, current.replaceAll, current.name)
			}

			is ExtractVariableUiEvent.NameChanged -> {
				_uiState.value =
					current.copy(
						name = event.name,
						nameProblem =
							validateVariableName(
								event.name,
								candidate(current.selectedCandidate).takenNames,
								keywords,
							),
					)
			}

			is ExtractVariableUiEvent.ReplaceAllChanged -> {
				_uiState.value = current.copy(replaceAll = event.replaceAll)
			}

			ExtractVariableUiEvent.Confirmed, ExtractVariableUiEvent.Dismissed -> {
				Unit
			}
		}
	}

	/** The user's decision, or null when the name is unusable. */
	fun selection(): ExtractVariableSelection? {
		val state = _uiState.value
		if (!state.canConfirm) return null
		return ExtractVariableSelection(
			candidateIndex = state.selectedCandidate,
			scopeIndex = state.selectedScope,
			name = state.name,
			// A single occurrence makes the toggle meaningless, and the sheet hides it; make sure a
			// stale `true` from a previous candidate cannot leak into the selection.
			replaceAll = state.replaceAll && state.occurrenceCount > 1,
		)
	}

	private fun candidate(index: Int) = candidates[index.coerceIn(candidates.indices)]

	/**
	 * Recomputes the whole state for a (candidate, scope) pair. [name] carries the user's typed name
	 * across a scope change; pass null to take the candidate's suggestion.
	 */
	private fun stateFor(
		candidateIndex: Int,
		scopeIndex: Int,
		replaceAll: Boolean,
		name: String?,
	): ExtractVariableUiState {
		val candidate = candidate(candidateIndex)
		val boundedScope = scopeIndex.coerceIn(candidate.scopes.indices)
		val scope = candidate.scopes[boundedScope]
		val resolvedName = name ?: candidate.suggestedName

		return ExtractVariableUiState(
			candidateLabels = candidates.map { it.label },
			selectedCandidate = candidateIndex.coerceIn(candidates.indices),
			showCandidatePicker = candidates.size > 1,
			name = resolvedName,
			nameProblem = validateVariableName(resolvedName, candidate.takenNames, keywords),
			scopeLabels = candidate.scopes.map { it.label },
			selectedScope = boundedScope,
			occurrenceCount = scope.occurrenceCount,
			replaceAll = replaceAll && scope.occurrenceCount > 1,
		)
	}

	companion object {
		fun factory(
			candidates: List<CandidateView>,
			keywords: Set<String>,
		): ViewModelProvider.Factory =
			object : ViewModelProvider.Factory {
				@Suppress("UNCHECKED_CAST")
				override fun <T : ViewModel> create(modelClass: Class<T>): T = ExtractVariableViewModel(candidates, keywords) as T
			}
	}
}
