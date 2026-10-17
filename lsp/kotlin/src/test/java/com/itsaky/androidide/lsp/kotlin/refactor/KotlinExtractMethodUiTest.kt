package com.itsaky.androidide.lsp.kotlin.refactor

import com.itsaky.androidide.lsp.kotlin.utils.refactor.CallSiteForm
import com.itsaky.androidide.lsp.kotlin.utils.refactor.ExtractMethodCandidate
import com.itsaky.androidide.lsp.kotlin.utils.refactor.ExtractMethodPlan
import com.itsaky.androidide.lsp.kotlin.utils.refactor.ExtractedBody
import com.itsaky.androidide.lsp.kotlin.utils.refactor.MethodParameter
import com.itsaky.androidide.lsp.kotlin.utils.refactor.signaturePrefix
import com.itsaky.androidide.lsp.kotlin.utils.refactor.signatureSuffix
import com.itsaky.androidide.lsp.refactor.TextSpan
import com.itsaky.androidide.lsp.ui.ExtractMethodSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KotlinExtractMethodUiTest {
	private fun candidate(
		label: String,
		receiverTypeText: String?,
		returnTypeText: String?,
	) = ExtractMethodCandidate(
		label = label,
		span = TextSpan(0, 1),
		suggestedName = "extracted",
		takenNames = setOf("a", "b"),
		annotations = emptyList(),
		modifiers = listOf("private"),
		receiverTypeText = receiverTypeText,
		parameters = listOf(MethodParameter("a", "Int")),
		returnTypeText = returnTypeText,
		body = ExtractedBody.ExpressionBody(needsReturn = true),
		callSite = CallSiteForm.Call,
		insertOffset = 0,
		insertIndent = "\t",
		rawStringSpans = emptyList(),
	)

	private fun plan(vararg candidates: ExtractMethodCandidate) =
		ExtractMethodPlan(
			fileText = "",
			documentVersion = null,
			candidates = candidates.toList(),
			refusal = null,
		)

	@Test
	fun `each view carries its candidate's two signature halves, not transposed`() {
		val c = candidate(label = "region", receiverTypeText = "String", returnTypeText = "Int")
		assertNotEquals(c.signaturePrefix, c.signatureSuffix)

		val view = plan(c).toMethodCandidateViews().single()
		assertEquals(c.signaturePrefix, view.signaturePrefix)
		assertEquals(c.signatureSuffix, view.signatureSuffix)
		assertEquals("region", view.label)
		assertEquals("extracted", view.suggestedName)
		assertEquals(setOf("a", "b"), view.takenNames)
	}

	@Test
	fun `candidateFor maps a selection index back to its candidate, or null when out of range`() {
		val first = candidate(label = "first", receiverTypeText = null, returnTypeText = "Int")
		val second = candidate(label = "second", receiverTypeText = "String", returnTypeText = null)
		val plan = plan(first, second)

		assertEquals(first, plan.candidateFor(ExtractMethodSelection(0, "extracted")))
		assertEquals(second, plan.candidateFor(ExtractMethodSelection(1, "extracted")))
		assertNull(plan.candidateFor(ExtractMethodSelection(2, "extracted")))
	}
}
