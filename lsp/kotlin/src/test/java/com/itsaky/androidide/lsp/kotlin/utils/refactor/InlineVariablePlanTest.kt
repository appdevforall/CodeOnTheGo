package com.itsaky.androidide.lsp.kotlin.utils.refactor

import com.itsaky.androidide.lsp.refactor.TextSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The plan's pure derivations: the mode table and the derived labels and reports. Analysis-free, so
 * the strings the user reads are pinned without a compilation environment.
 */
class InlineVariablePlanTest {
	private fun plan(
		references: List<InlineReference>,
		canDeleteDeclaration: Boolean = true,
		cursorPosition: InlineCursorPosition = InlineCursorPosition.Declaration,
		cursorReferenceIndex: Int = -1,
	) = InlineVariablePlan(
		fileText = "",
		documentVersion = 1,
		variableName = "total",
		declarationSpan = TextSpan(0, 0),
		initializerText = "a + b",
		initializerNeedsParentheses = true,
		references = references,
		cursorPosition = cursorPosition,
		cursorReferenceIndex = cursorReferenceIndex,
		canDeleteDeclaration = canDeleteDeclaration,
		modes = modesFor(cursorPosition, references.count { it.isInlinable }),
		refusal = null,
	)

	private fun reference(exclusion: InlineExclusion? = null) =
		InlineReference(span = TextSpan(0, 0), isShortTemplateEntry = false, exclusion = exclusion)

	@Test
	fun `a cursor on the declaration offers only all references`() {
		assertEquals(listOf(InlineMode.AllReferences), modesFor(InlineCursorPosition.Declaration, 3))
	}

	@Test
	fun `a cursor on a reference with two or more inlinable offers both modes`() {
		assertEquals(
			listOf(InlineMode.ThisReferenceOnly, InlineMode.AllReferences),
			modesFor(InlineCursorPosition.Reference, 2),
		)
	}

	@Test
	fun `a cursor on a reference with one inlinable collapses to all references`() {
		// "This reference only" would leave a declaration nothing reads -- a `never used` warning in
		// generated code that this refactoring must decline rather than emit.
		assertEquals(listOf(InlineMode.AllReferences), modesFor(InlineCursorPosition.Reference, 1))
	}

	@Test
	fun `the all-references label says the declaration goes when nothing is left behind`() {
		val result = plan(listOf(reference(), reference(), reference()))

		assertTrue(result.offersChoice.not())
		assertEquals(InlineLabel.AllAndDelete(3, "total"), result.labelFor(InlineMode.AllReferences))
	}

	@Test
	fun `the all-references label keeps the declaration when a write survives`() {
		val result = plan(listOf(reference(), reference()), canDeleteDeclaration = false)

		assertEquals(InlineLabel.AllKeepingDeclaration(2, "total"), result.labelFor(InlineMode.AllReferences))
	}

	@Test
	fun `the all-references label states both counts for a partial inline`() {
		val result = plan(listOf(reference(), reference(InlineExclusion.PastCutoff)), canDeleteDeclaration = false)

		assertEquals(
			InlineLabel.PartialKeepingDeclaration(1, 2, "total"),
			result.labelFor(InlineMode.AllReferences),
		)
	}

	@Test
	fun `this-reference-only has a fixed label and always keeps the declaration`() {
		val result =
			plan(
				listOf(reference(), reference()),
				cursorPosition = InlineCursorPosition.Reference,
				cursorReferenceIndex = 0,
			)

		assertEquals(InlineLabel.ThisReferenceOnly, result.labelFor(InlineMode.ThisReferenceOnly))
		assertEquals(
			InlineReport.InlinedPartially(1, 2, "total"),
			result.reportFor(InlineMode.ThisReferenceOnly),
		)
	}

	@Test
	fun `the whole-inline report names the count and the removed declaration`() {
		val result = plan(listOf(reference(), reference(), reference()))

		assertEquals(InlineReport.InlinedAndRemoved(3, "total"), result.reportFor(InlineMode.AllReferences))
	}

	@Test
	fun `the partial report says both counts`() {
		val result =
			plan(
				listOf(reference(), reference(), reference(InlineExclusion.SmartCast)),
				canDeleteDeclaration = false,
			)

		assertEquals(InlineReport.InlinedPartially(2, 3, "total"), result.reportFor(InlineMode.AllReferences))
	}

	@Test
	fun `a report distinguishes all-inlined-but-kept from partial`() {
		val result = plan(listOf(reference(), reference()), canDeleteDeclaration = false)

		assertEquals(
			InlineReport.InlinedKeepingDeclaration(2, "total"),
			result.reportFor(InlineMode.AllReferences),
		)
	}

	@Test
	fun `a refused plan carries no references and no modes`() {
		val refused = InlineVariablePlan.refused(InlineRefusal.CouldNotAnalyse)

		assertTrue(refused.isRefused)
		assertFalse(refused.offersChoice)
		assertEquals(emptyList<InlineReference>(), refused.references)
	}
}
