package com.itsaky.androidide.lsp.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sheet's derivation logic, tested without Compose, a fragment or an activity.
 *
 * Every choice the sheet offers is recomputed from the candidate views it was given, so all of this is
 * exercisable as plain state transitions -- which is the point of the sheet taking a view of a plan
 * rather than the plan itself.
 */
class ExtractVariableViewModelTest {
	private fun scope(
		label: String,
		occurrences: Int,
	) = ScopeView(label = label, occurrenceCount = occurrences)

	private fun candidate(
		label: String,
		suggestedName: String,
		scopes: List<ScopeView>,
		takenNames: Set<String> = emptySet(),
	) = CandidateView(
		label = label,
		suggestedName = suggestedName,
		takenNames = takenNames,
		scopes = scopes,
	)

	private fun plan(candidates: List<CandidateView>) = candidates

	private val threeCandidatePlan =
		plan(
			listOf(
				candidate("items.size", "size", listOf(scope("lambda", 1), scope("fun demo", 3))),
				candidate("items.size * 2", "size1", listOf(scope("fun demo", 2))),
				candidate("wrap(items.size * 2)", "wrap", listOf(scope("fun demo", 1))),
			),
		)

	@Test
	fun `starts on the innermost candidate, innermost scope, replace-all off`() {
		val state = viewModelFor(threeCandidatePlan).uiState.value

		assertEquals(0, state.selectedCandidate)
		assertEquals(0, state.selectedScope)
		assertEquals("size", state.name)
		assertFalse(state.replaceAll)
		assertTrue(state.canConfirm)
	}

	@Test
	fun `shows the candidate picker only when there is a real choice`() {
		assertTrue(viewModelFor(threeCandidatePlan).uiState.value.showCandidatePicker)

		val single = plan(listOf(candidate("items.size", "size", listOf(scope("fun demo", 1)))))
		assertFalse(viewModelFor(single).uiState.value.showCandidatePicker)
	}

	@Test
	fun `changing the expression re-derives name, scopes and count`() {
		val viewModel = viewModelFor(threeCandidatePlan)

		viewModel.onEvent(ExtractVariableUiEvent.CandidateSelected(1))
		val state = viewModel.uiState.value

		assertEquals("size1", state.name)
		assertEquals(listOf("fun demo"), state.scopeLabels)
		assertEquals(2, state.occurrenceCount)
		assertEquals(0, state.selectedScope)
	}

	@Test
	fun `changing the scope changes the occurrence count`() {
		val viewModel = viewModelFor(threeCandidatePlan)
		assertEquals(1, viewModel.uiState.value.occurrenceCount)

		viewModel.onEvent(ExtractVariableUiEvent.ScopeSelected(1))

		assertEquals(1, viewModel.uiState.value.selectedScope)
		assertEquals(3, viewModel.uiState.value.occurrenceCount)
	}

	@Test
	fun `a scope change keeps the name the user typed`() {
		val viewModel = viewModelFor(threeCandidatePlan)
		viewModel.onEvent(ExtractVariableUiEvent.NameChanged("mySize"))

		viewModel.onEvent(ExtractVariableUiEvent.ScopeSelected(1))

		assertEquals("mySize", viewModel.uiState.value.name)
	}

	@Test
	fun `the replace-all toggle is hidden at a single occurrence`() {
		val viewModel = viewModelFor(threeCandidatePlan)
		assertFalse(viewModel.uiState.value.showReplaceAll)

		viewModel.onEvent(ExtractVariableUiEvent.ScopeSelected(1))

		assertTrue(viewModel.uiState.value.showReplaceAll)
	}

	@Test
	fun `an invalid name blocks confirming`() {
		val viewModel = viewModelFor(threeCandidatePlan)

		viewModel.onEvent(ExtractVariableUiEvent.NameChanged("val"))

		assertEquals(NameProblem.Keyword, viewModel.uiState.value.nameProblem)
		assertFalse(viewModel.uiState.value.canConfirm)
		assertNull(viewModel.selection())
	}

	@Test
	fun `a name colliding with a visible declaration is rejected`() {
		val colliding =
			plan(listOf(candidate("items.size", "size1", listOf(scope("fun demo", 1)), takenNames = setOf("size"))))
		val viewModel = viewModelFor(colliding)

		viewModel.onEvent(ExtractVariableUiEvent.NameChanged("size"))

		assertEquals(NameProblem.AlreadyTaken, viewModel.uiState.value.nameProblem)
	}

	@Test
	fun `the selection carries the chosen expression, scope, name and toggle`() {
		val viewModel = viewModelFor(threeCandidatePlan)
		viewModel.onEvent(ExtractVariableUiEvent.ScopeSelected(1))
		viewModel.onEvent(ExtractVariableUiEvent.ReplaceAllChanged(true))
		viewModel.onEvent(ExtractVariableUiEvent.NameChanged("total"))

		val selection = viewModel.selection()
		assertNotNull(selection)
		// Indices, not resolved objects: mapping them back to a candidate is the caller's job.
		assertEquals(0, selection!!.candidateIndex)
		assertEquals(1, selection.scopeIndex)
		assertEquals("total", selection.name)
		assertTrue(selection.replaceAll)
	}

	@Test
	fun `replace-all cannot leak from a wider scope into a single-occurrence one`() {
		val viewModel = viewModelFor(threeCandidatePlan)
		viewModel.onEvent(ExtractVariableUiEvent.ScopeSelected(1))
		viewModel.onEvent(ExtractVariableUiEvent.ReplaceAllChanged(true))
		assertTrue(viewModel.uiState.value.replaceAll)

		// Back to the lambda scope, which has one occurrence and no visible toggle.
		viewModel.onEvent(ExtractVariableUiEvent.ScopeSelected(0))

		assertFalse(viewModel.uiState.value.replaceAll)
		assertFalse(viewModel.selection()!!.replaceAll)
	}

	@Test
	fun `switching expression resets replace-all`() {
		val viewModel = viewModelFor(threeCandidatePlan)
		viewModel.onEvent(ExtractVariableUiEvent.ScopeSelected(1))
		viewModel.onEvent(ExtractVariableUiEvent.ReplaceAllChanged(true))

		viewModel.onEvent(ExtractVariableUiEvent.CandidateSelected(1))

		assertFalse(viewModel.uiState.value.replaceAll)
	}

	/**
	 * The keyword set is language-specific and irrelevant to state derivation, so every case here uses
	 * a small Kotlin-shaped one; each language's own suite covers its real set.
	 */
	private fun viewModelFor(candidates: List<CandidateView>) = ExtractVariableViewModel(candidates, KEYWORDS)

	private companion object {
		private val KEYWORDS = setOf("val", "var", "fun", "when", "this")
	}
}
