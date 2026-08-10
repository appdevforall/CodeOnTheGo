package com.itsaky.androidide.lsp.kotlin.refactor.ui

import com.itsaky.androidide.lsp.kotlin.utils.refactor.CallSiteForm
import com.itsaky.androidide.lsp.kotlin.utils.refactor.ExtractMethodCandidate
import com.itsaky.androidide.lsp.kotlin.utils.refactor.ExtractMethodPlan
import com.itsaky.androidide.lsp.kotlin.utils.refactor.ExtractedBody
import com.itsaky.androidide.lsp.kotlin.utils.refactor.MethodParameter
import com.itsaky.androidide.lsp.kotlin.utils.refactor.NameProblem
import com.itsaky.androidide.lsp.kotlin.utils.refactor.TextSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The sheet's derivation logic, tested without Compose, a fragment or an activity. */
class ExtractMethodViewModelTest {
	private fun candidate(
		label: String,
		suggestedName: String,
		parameters: List<MethodParameter> = listOf(MethodParameter("a", "Int")),
		returnTypeText: String? = "Int",
		modifiers: List<String> = listOf("private"),
		takenNames: Set<String> = emptySet(),
	) = ExtractMethodCandidate(
		label = label,
		span = TextSpan(0, 5),
		suggestedName = suggestedName,
		takenNames = takenNames,
		annotations = emptyList(),
		modifiers = modifiers,
		receiverTypeText = null,
		parameters = parameters,
		returnTypeText = returnTypeText,
		body = ExtractedBody.ExpressionBody(needsReturn = true),
		callSite = CallSiteForm.Call,
		insertOffset = 100,
		insertIndent = "\t",
	)

	private fun plan(
		candidates: List<ExtractMethodCandidate>,
		selectionMatched: Boolean = false,
	) = ExtractMethodPlan(
		fileText = "unused",
		documentVersion = 1,
		candidates = candidates,
		selectionMatchedCandidate = selectionMatched,
		refusal = null,
	)

	@Test
	fun `the initial state takes the first candidate's suggestion`() {
		val model = ExtractMethodViewModel(plan(listOf(candidate("a + b", "total"))))

		assertEquals("total", model.uiState.value.name)
		assertEquals(0, model.uiState.value.selectedCandidate)
		assertNull(model.uiState.value.nameProblem)
	}

	@Test
	fun `the chooser is hidden for one candidate and for an exact selection match`() {
		val single = ExtractMethodViewModel(plan(listOf(candidate("a + b", "total"))))
		assertFalse(single.uiState.value.showCandidatePicker)

		val many = listOf(candidate("a + b", "total"), candidate("a + b + c", "total1"))
		assertTrue(ExtractMethodViewModel(plan(many)).uiState.value.showCandidatePicker)
		assertFalse(ExtractMethodViewModel(plan(many, selectionMatched = true)).uiState.value.showCandidatePicker)
	}

	@Test
	fun `the preview is the signature as it will be emitted`() {
		val model =
			ExtractMethodViewModel(
				plan(
					listOf(
						candidate(
							"load() + 1",
							"total",
							parameters = listOf(MethodParameter("id", "String")),
							returnTypeText = "User",
							modifiers = listOf("private", "suspend"),
						),
					),
				),
			)

		assertEquals("private suspend fun total(id: String): User", model.uiState.value.signaturePreview)

		model.onEvent(ExtractMethodUiEvent.NameChanged("loadUser"))

		assertEquals("private suspend fun loadUser(id: String): User", model.uiState.value.signaturePreview)
	}

	@Test
	fun `a name matching an inherited member is rejected`() {
		val model =
			ExtractMethodViewModel(plan(listOf(candidate("a + b", "total", takenNames = setOf("helper")))))

		model.onEvent(ExtractMethodUiEvent.NameChanged("helper"))

		assertEquals(NameProblem.AlreadyTaken, model.uiState.value.nameProblem)
		assertFalse(model.uiState.value.canConfirm)
		assertNull(model.choice())
	}

	@Test
	fun `switching candidate re-suggests the name`() {
		val model =
			ExtractMethodViewModel(
				plan(listOf(candidate("a + b", "total"), candidate("a + b + c", "sum"))),
			)
		model.onEvent(ExtractMethodUiEvent.NameChanged("mine"))

		model.onEvent(ExtractMethodUiEvent.CandidateSelected(1))

		assertEquals("sum", model.uiState.value.name)
		assertEquals(1, model.uiState.value.selectedCandidate)
	}

	@Test
	fun `the choice carries the selected candidate and the typed name`() {
		val model =
			ExtractMethodViewModel(
				plan(listOf(candidate("a + b", "total"), candidate("a + b + c", "sum"))),
			)
		model.onEvent(ExtractMethodUiEvent.CandidateSelected(1))
		model.onEvent(ExtractMethodUiEvent.NameChanged("combined"))

		val choice = model.choice()

		assertNotNull(choice)
		assertEquals("a + b + c", choice!!.candidate.label)
		assertEquals("combined", choice.name)
	}

	@Test
	fun `a blank name blocks confirmation`() {
		val model = ExtractMethodViewModel(plan(listOf(candidate("a + b", "total"))))

		model.onEvent(ExtractMethodUiEvent.NameChanged(""))

		assertEquals(NameProblem.Blank, model.uiState.value.nameProblem)
		assertNull(model.choice())
	}
}
