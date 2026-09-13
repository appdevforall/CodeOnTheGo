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
 * The keyword set is passed in rather than looked up, so both languages' shapes are exercised here:
 * Kotlin's `fun name(a: Int): Int` and Java's `int name(int a) throws IOException`.
 */
class ExtractMethodViewModelTest {
	private val kotlinKeywords = setOf("when", "val", "fun")
	private val javaKeywords = setOf("int", "static", "class")

	private fun candidate(
		label: String,
		suggestedName: String,
		signaturePrefix: String = "private fun ",
		signatureSuffix: String = "(a: Int): Int",
		takenNames: Set<String> = emptySet(),
	) = MethodCandidateView(
		label = label,
		suggestedName = suggestedName,
		takenNames = takenNames,
		signaturePrefix = signaturePrefix,
		signatureSuffix = signatureSuffix,
	)

	private fun model(
		candidates: List<MethodCandidateView>,
		keywords: Set<String> = kotlinKeywords,
	) = ExtractMethodViewModel(candidates, keywords)

	@Test
	fun `the initial state takes the first candidate's suggestion`() {
		val model = model(listOf(candidate("a + b", "total")))

		assertEquals("total", model.uiState.value.name)
		assertEquals(0, model.uiState.value.selectedCandidate)
		assertNull(model.uiState.value.nameProblem)
	}

	@Test
	fun `the chooser is hidden for one candidate and shown for more`() {
		val single = model(listOf(candidate("a + b", "total")))
		assertFalse(single.uiState.value.showCandidatePicker)

		val many = listOf(candidate("a + b", "total"), candidate("a + b + c", "total1"))
		assertTrue(model(many).uiState.value.showCandidatePicker)
	}

	@Test
	fun `the preview is the Kotlin signature as it will be emitted`() {
		val model =
			model(
				listOf(
					candidate(
						"load() + 1",
						"total",
						signaturePrefix = "private suspend fun ",
						signatureSuffix = "(id: String): User",
					),
				),
			)

		assertEquals("private suspend fun total(id: String): User", model.uiState.value.signaturePreview)

		model.onEvent(ExtractMethodUiEvent.NameChanged("loadUser"))

		assertEquals("private suspend fun loadUser(id: String): User", model.uiState.value.signaturePreview)
	}

	@Test
	fun `the preview is the Java signature as it will be emitted`() {
		val model =
			model(
				listOf(
					candidate(
						"read(path)",
						"read",
						signaturePrefix = "private static int ",
						signatureSuffix = "(int a, int b) throws IOException",
					),
				),
				javaKeywords,
			)

		assertEquals("private static int read(int a, int b) throws IOException", model.uiState.value.signaturePreview)

		model.onEvent(ExtractMethodUiEvent.NameChanged("readTotal"))

		assertEquals(
			"private static int readTotal(int a, int b) throws IOException",
			model.uiState.value.signaturePreview,
		)
	}

	@Test
	fun `a name matching an inherited member is rejected`() {
		val model = model(listOf(candidate("a + b", "total", takenNames = setOf("helper"))))

		model.onEvent(ExtractMethodUiEvent.NameChanged("helper"))

		assertEquals(NameProblem.AlreadyTaken, model.uiState.value.nameProblem)
		assertFalse(model.uiState.value.canConfirm)
		assertNull(model.selection())
	}

	@Test
	fun `switching candidate re-suggests the name`() {
		val model = model(listOf(candidate("a + b", "total"), candidate("a + b + c", "sum")))
		model.onEvent(ExtractMethodUiEvent.NameChanged("mine"))

		model.onEvent(ExtractMethodUiEvent.CandidateSelected(1))

		assertEquals("sum", model.uiState.value.name)
		assertEquals(1, model.uiState.value.selectedCandidate)
	}

	@Test
	fun `the selection carries the selected candidate and the typed name`() {
		val model = model(listOf(candidate("a + b", "total"), candidate("a + b + c", "sum")))
		model.onEvent(ExtractMethodUiEvent.CandidateSelected(1))
		model.onEvent(ExtractMethodUiEvent.NameChanged("combined"))

		val selection = model.selection()

		assertNotNull(selection)
		assertEquals(1, selection!!.candidateIndex)
		assertEquals("combined", selection.name)
	}

	@Test
	fun `a blank name blocks confirmation`() {
		val model = model(listOf(candidate("a + b", "total")))

		model.onEvent(ExtractMethodUiEvent.NameChanged(""))

		assertEquals(NameProblem.Blank, model.uiState.value.nameProblem)
		assertNull(model.selection())
	}

	@Test
	fun `a keyword blocks confirmation in either language`() {
		val kotlin = model(listOf(candidate("a + b", "total")))
		kotlin.onEvent(ExtractMethodUiEvent.NameChanged("when"))
		assertEquals(NameProblem.Keyword, kotlin.uiState.value.nameProblem)
		assertNull(kotlin.selection())

		val java = model(listOf(candidate("a + b", "total")), javaKeywords)
		java.onEvent(ExtractMethodUiEvent.NameChanged("static"))
		assertEquals(NameProblem.Keyword, java.uiState.value.nameProblem)
		assertNull(java.selection())
	}

	@Test
	fun `a name that only looks like a keyword is accepted`() {
		val model = model(listOf(candidate("a + b", "total")))

		model.onEvent(ExtractMethodUiEvent.NameChanged("whenever"))

		assertNull(model.uiState.value.nameProblem)
		assertTrue(model.uiState.value.canConfirm)
	}
}
