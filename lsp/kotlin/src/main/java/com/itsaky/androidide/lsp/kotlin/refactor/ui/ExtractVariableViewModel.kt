package com.itsaky.androidide.lsp.kotlin.refactor.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.itsaky.androidide.lsp.kotlin.utils.refactor.ExtractionPlan
import com.itsaky.androidide.lsp.kotlin.utils.refactor.validateVariableName
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Derives the sheet's state from an [ExtractionPlan] and nothing else.
 *
 * The plan already contains every candidate's scope chain and occurrence set, so switching expression
 * or scope is pure recomputation -- no analysis, no PSI, no I/O. That is what lets this class hold all
 * the sheet's logic while remaining a plain unit test.
 *
 * A plain [ViewModelProvider.Factory] rather than a Koin definition (ADR 0006/0009 resolve ViewModels
 * through Koin): this one is sheet-scoped, injects nothing, and takes the plan as a runtime argument,
 * so a Koin definition would add indirection without providing anything.
 */
class ExtractVariableViewModel(
	private val plan: ExtractionPlan,
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
				_uiState.value =
					stateFor(current.selectedCandidate, event.index, current.replaceAll, current.name)
			}

			is ExtractVariableUiEvent.NameChanged -> {
				_uiState.value =
					current.copy(
						name = event.name,
						nameProblem = validateVariableName(event.name, candidate(current.selectedCandidate).takenNames),
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
	fun choice(): ExtractionChoice? {
		val state = _uiState.value
		if (!state.canConfirm) return null
		val candidate = candidate(state.selectedCandidate)
		val scope = candidate.scopes.getOrNull(state.selectedScope) ?: return null
		return ExtractionChoice(
			candidate = candidate,
			scope = scope,
			name = state.name,
			// A single occurrence makes the toggle meaningless, and the sheet hides it; make sure a
			// stale `true` from a previous candidate cannot leak into the choice.
			replaceAll = state.replaceAll && state.occurrenceCount > 1,
		)
	}

	private fun candidate(index: Int) = plan.candidates[index.coerceIn(plan.candidates.indices)]

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
		val occurrenceCount = scope.occurrences.size

		return ExtractVariableUiState(
			candidateLabels = plan.candidates.map { it.label },
			selectedCandidate = candidateIndex.coerceIn(plan.candidates.indices),
			showCandidatePicker = plan.candidates.size > 1 && !plan.selectionMatchedCandidate,
			name = resolvedName,
			nameProblem = validateVariableName(resolvedName, candidate.takenNames),
			scopeLabels = candidate.scopes.map { it.label },
			selectedScope = boundedScope,
			occurrenceCount = occurrenceCount,
			replaceAll = replaceAll && occurrenceCount > 1,
		)
	}

	companion object {
		fun factory(plan: ExtractionPlan): ViewModelProvider.Factory =
			object : ViewModelProvider.Factory {
				@Suppress("UNCHECKED_CAST")
				override fun <T : ViewModel> create(modelClass: Class<T>): T = ExtractVariableViewModel(plan) as T
			}
	}
}
