package com.itsaky.androidide.lsp.kotlin.refactor.ui

import com.itsaky.androidide.lsp.kotlin.utils.refactor.AnchorForm
import com.itsaky.androidide.lsp.kotlin.utils.refactor.CandidateExpression
import com.itsaky.androidide.lsp.kotlin.utils.refactor.ExtractionPlan
import com.itsaky.androidide.lsp.kotlin.utils.refactor.NameProblem
import com.itsaky.androidide.lsp.kotlin.utils.refactor.ScopeOption
import com.itsaky.androidide.lsp.kotlin.utils.refactor.TextSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sheet's derivation logic, tested without Compose, a fragment or an activity.
 *
 * Every choice the sheet offers is recomputed from the plan, so all of this is exercisable as plain
 * state transitions -- which is the point of keeping the plan plain data.
 */
class ExtractVariableViewModelTest {
	private fun scope(
		label: String,
		occurrences: Int,
	) = ScopeOption(
		label = label,
		anchorForm = AnchorForm.ExistingBlock,
		occurrences = (0 until occurrences).map { TextSpan(it * 10, it * 10 + 5) },
	)

	private fun candidate(
		label: String,
		suggestedName: String,
		scopes: List<ScopeOption>,
		takenNames: Set<String> = emptySet(),
	) = CandidateExpression(
		label = label,
		span = TextSpan(0, 5),
		suggestedName = suggestedName,
		takenNames = takenNames,
		scopes = scopes,
	)

	private fun plan(
		candidates: List<CandidateExpression>,
		selectionMatched: Boolean = false,
	) = ExtractionPlan(
		fileText = "unused",
		documentVersion = 1,
		candidates = candidates,
		selectionMatchedCandidate = selectionMatched,
	)

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
		val state = ExtractVariableViewModel(threeCandidatePlan).uiState.value

		assertEquals(0, state.selectedCandidate)
		assertEquals(0, state.selectedScope)
		assertEquals("size", state.name)
		assertFalse(state.replaceAll)
		assertTrue(state.canConfirm)
	}

	@Test
	fun `shows the candidate picker only when there is a real choice`() {
		assertTrue(ExtractVariableViewModel(threeCandidatePlan).uiState.value.showCandidatePicker)

		val single = plan(listOf(candidate("items.size", "size", listOf(scope("fun demo", 1)))))
		assertFalse(ExtractVariableViewModel(single).uiState.value.showCandidatePicker)
	}

	@Test
	fun `an exact selection suppresses the candidate picker`() {
		// The user already said which expression they meant by selecting it.
		val matched = plan(threeCandidatePlan.candidates, selectionMatched = true)
		assertFalse(ExtractVariableViewModel(matched).uiState.value.showCandidatePicker)
	}

	@Test
	fun `changing the expression re-derives name, scopes and count`() {
		val viewModel = ExtractVariableViewModel(threeCandidatePlan)

		viewModel.onEvent(ExtractVariableUiEvent.CandidateSelected(1))
		val state = viewModel.uiState.value

		assertEquals("size1", state.name)
		assertEquals(listOf("fun demo"), state.scopeLabels)
		assertEquals(2, state.occurrenceCount)
		assertEquals(0, state.selectedScope)
	}

	@Test
	fun `changing the scope changes the occurrence count`() {
		val viewModel = ExtractVariableViewModel(threeCandidatePlan)
		assertEquals(1, viewModel.uiState.value.occurrenceCount)

		viewModel.onEvent(ExtractVariableUiEvent.ScopeSelected(1))

		assertEquals(1, viewModel.uiState.value.selectedScope)
		assertEquals(3, viewModel.uiState.value.occurrenceCount)
	}

	@Test
	fun `a scope change keeps the name the user typed`() {
		val viewModel = ExtractVariableViewModel(threeCandidatePlan)
		viewModel.onEvent(ExtractVariableUiEvent.NameChanged("mySize"))

		viewModel.onEvent(ExtractVariableUiEvent.ScopeSelected(1))

		assertEquals("mySize", viewModel.uiState.value.name)
	}

	@Test
	fun `the replace-all toggle is hidden at a single occurrence`() {
		val viewModel = ExtractVariableViewModel(threeCandidatePlan)
		assertFalse(viewModel.uiState.value.showReplaceAll)

		viewModel.onEvent(ExtractVariableUiEvent.ScopeSelected(1))

		assertTrue(viewModel.uiState.value.showReplaceAll)
	}

	@Test
	fun `an invalid name blocks confirming`() {
		val viewModel = ExtractVariableViewModel(threeCandidatePlan)

		viewModel.onEvent(ExtractVariableUiEvent.NameChanged("val"))

		assertEquals(NameProblem.Keyword, viewModel.uiState.value.nameProblem)
		assertFalse(viewModel.uiState.value.canConfirm)
		assertNull(viewModel.choice())
	}

	@Test
	fun `a name colliding with a visible declaration is rejected`() {
		val colliding =
			plan(listOf(candidate("items.size", "size1", listOf(scope("fun demo", 1)), takenNames = setOf("size"))))
		val viewModel = ExtractVariableViewModel(colliding)

		viewModel.onEvent(ExtractVariableUiEvent.NameChanged("size"))

		assertEquals(NameProblem.AlreadyTaken, viewModel.uiState.value.nameProblem)
	}

	@Test
	fun `the choice carries the selected expression, scope, name and toggle`() {
		val viewModel = ExtractVariableViewModel(threeCandidatePlan)
		viewModel.onEvent(ExtractVariableUiEvent.ScopeSelected(1))
		viewModel.onEvent(ExtractVariableUiEvent.ReplaceAllChanged(true))
		viewModel.onEvent(ExtractVariableUiEvent.NameChanged("total"))

		val choice = viewModel.choice()
		assertNotNull(choice)
		assertEquals("items.size", choice!!.candidate.label)
		assertEquals("fun demo", choice.scope.label)
		assertEquals("total", choice.name)
		assertTrue(choice.replaceAll)
	}

	@Test
	fun `replace-all cannot leak from a wider scope into a single-occurrence one`() {
		val viewModel = ExtractVariableViewModel(threeCandidatePlan)
		viewModel.onEvent(ExtractVariableUiEvent.ScopeSelected(1))
		viewModel.onEvent(ExtractVariableUiEvent.ReplaceAllChanged(true))
		assertTrue(viewModel.uiState.value.replaceAll)

		// Back to the lambda scope, which has one occurrence and no visible toggle.
		viewModel.onEvent(ExtractVariableUiEvent.ScopeSelected(0))

		assertFalse(viewModel.uiState.value.replaceAll)
		assertFalse(viewModel.choice()!!.replaceAll)
	}

	@Test
	fun `switching expression resets replace-all`() {
		val viewModel = ExtractVariableViewModel(threeCandidatePlan)
		viewModel.onEvent(ExtractVariableUiEvent.ScopeSelected(1))
		viewModel.onEvent(ExtractVariableUiEvent.ReplaceAllChanged(true))

		viewModel.onEvent(ExtractVariableUiEvent.CandidateSelected(1))

		assertFalse(viewModel.uiState.value.replaceAll)
	}
}
