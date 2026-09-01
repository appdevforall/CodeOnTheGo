package com.itsaky.androidide.lsp.kotlin.utils.refactor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The analysis-free primitives the refactoring is built from: name rules, indentation, soundness. */
class RefactorPrimitivesTest {
	@Test
	fun `rejects blank names`() {
		assertEquals(NameProblem.Blank, validateVariableName("", emptySet()))
		assertEquals(NameProblem.Blank, validateVariableName("   ", emptySet()))
	}

	@Test
	fun `rejects non-identifiers`() {
		assertEquals(NameProblem.NotAnIdentifier, validateVariableName("1size", emptySet()))
		assertEquals(NameProblem.NotAnIdentifier, validateVariableName("my size", emptySet()))
		assertEquals(NameProblem.NotAnIdentifier, validateVariableName("size!", emptySet()))
		// Backticked names are legal Kotlin but deliberately unsupported for a generated local.
		assertEquals(NameProblem.NotAnIdentifier, validateVariableName("`size`", emptySet()))
	}

	@Test
	fun `rejects hard keywords but allows soft ones`() {
		assertEquals(NameProblem.Keyword, validateVariableName("val", emptySet()))
		assertEquals(NameProblem.Keyword, validateVariableName("when", emptySet()))
		assertEquals(NameProblem.Keyword, validateVariableName("this", emptySet()))
		// `it`, `data` and `by` are soft keywords -- perfectly legal identifiers.
		assertNull(validateVariableName("it", emptySet()))
		assertNull(validateVariableName("data", emptySet()))
		assertNull(validateVariableName("by", emptySet()))
	}

	@Test
	fun `rejects names already in use`() {
		assertEquals(NameProblem.AlreadyTaken, validateVariableName("size", setOf("size")))
		assertNull(validateVariableName("size", setOf("count")))
	}

	@Test
	fun `accepts underscores and digits`() {
		assertNull(validateVariableName("_size", emptySet()))
		assertNull(validateVariableName("size2", emptySet()))
	}

	@Test
	fun `detects a tab indent unit`() {
		assertEquals("\t", detectIndentUnit("fun f() {\n\tval x = 1\n}"))
	}

	@Test
	fun `detects the smallest space indent unit`() {
		assertEquals("  ", detectIndentUnit("fun f() {\n  val x = 1\n    val y = 2\n}"))
		assertEquals("    ", detectIndentUnit("fun f() {\n    val x = 1\n}"))
	}

	@Test
	fun `falls back to a tab when nothing is indented`() {
		assertEquals("\t", detectIndentUnit("fun f() {}"))
	}

	@Test
	fun `leading indent is read from the offset's own line`() {
		val text = "class C {\n\t\tval x = 1\n}"
		assertEquals("\t\t", leadingIndentAt(text, text.indexOf("val x")))
		assertEquals("", leadingIndentAt(text, text.indexOf("class")))
	}

	@Test
	fun `line start is found for the first and later lines`() {
		val text = "aa\nbbb\nc"
		assertEquals(0, lineStartOffset(text, 1))
		assertEquals(3, lineStartOffset(text, 4))
		assertEquals(7, lineStartOffset(text, 7))
	}

	@Test
	fun `label collapses whitespace and truncates`() {
		assertEquals("items.filter { it > 0 }", collapseForLabel("items\n\t.filter {   it > 0 }"))
		assertEquals("a?.b", collapseForLabel("a\n\t?.b"))
		assertEquals("aaaaaaa...", collapseForLabel("aaaaaaaaaaaa", maxLength = 10))
	}

	@Test
	fun `trim drops surrounding whitespace from a selection`() {
		val text = "  items.size  "
		assertEquals(2 to 12, trimToCode(text, 0, text.length))
	}

	@Test
	fun `trim leaves a cursor untouched and collapses a whitespace-only selection`() {
		assertEquals(3 to 3, trimToCode("a  b", 3, 3))
		// A drag over whitespace is the same intent as a tap in it: resolve from where it started.
		assertEquals(1 to 1, trimToCode("a    b", 1, 5))
		assertNull(trimToCode("a", 0, 5))
		assertNull(trimToCode("a", -1, 1))
		assertNull(trimToCode("abc", 2, 1))
	}

	@Test
	fun `soundness keeps every occurrence when nothing is written`() {
		val occurrences = listOf(TextSpan(10, 20), TextSpan(30, 40), TextSpan(50, 60))
		assertEquals(
			occurrences,
			excludeUnsoundOccurrences(occurrences, TextSpan(30, 40), writeOffsets = emptyList()),
		)
	}

	@Test
	fun `soundness drops occurrences separated from the candidate by a write`() {
		val occurrences = listOf(TextSpan(10, 20), TextSpan(30, 40), TextSpan(50, 60))
		// A reassignment between the second and third sites: the third no longer holds the same value.
		assertEquals(
			listOf(TextSpan(10, 20), TextSpan(30, 40)),
			excludeUnsoundOccurrences(occurrences, TextSpan(30, 40), writeOffsets = listOf(45)),
		)
	}

	@Test
	fun `soundness drops earlier occurrences when the write precedes the candidate`() {
		val occurrences = listOf(TextSpan(10, 20), TextSpan(30, 40), TextSpan(50, 60))
		assertEquals(
			listOf(TextSpan(30, 40), TextSpan(50, 60)),
			excludeUnsoundOccurrences(occurrences, TextSpan(30, 40), writeOffsets = listOf(25)),
		)
	}

	@Test
	fun `soundness always keeps the occurrence the user selected`() {
		val occurrences = listOf(TextSpan(10, 20), TextSpan(30, 40), TextSpan(50, 60))
		// Writes on both sides isolate the candidate, but it must never be dropped.
		assertEquals(
			listOf(TextSpan(30, 40)),
			excludeUnsoundOccurrences(occurrences, TextSpan(30, 40), writeOffsets = listOf(25, 45)),
		)
	}

	@Test
	fun `soundness falls back to the candidate alone when it is not among the occurrences`() {
		assertEquals(
			listOf(TextSpan(70, 80)),
			excludeUnsoundOccurrences(listOf(TextSpan(10, 20)), TextSpan(70, 80), writeOffsets = emptyList()),
		)
	}

	@Test
	fun `shortens types from Kotlin's default-imported packages`() {
		assertEquals("Int", shortenTypeText("kotlin.Int", emptySet(), emptySet()))
		assertEquals(
			"List<String>",
			shortenTypeText("kotlin.collections.List<kotlin.String>", emptySet(), emptySet()),
		)
	}

	@Test
	fun `keeps a type qualified when its short name would not resolve`() {
		assertEquals("java.util.Date", shortenTypeText("java.util.Date", emptySet(), emptySet()))
		// An import of the enclosing class is not an import of the nested one.
		assertEquals(
			"com.example.Outer.Inner",
			shortenTypeText("com.example.Outer.Inner", setOf("com.example.Outer"), emptySet()),
		)
	}

	@Test
	fun `shortens a type the file already imports, by name or by star`() {
		assertEquals("Date", shortenTypeText("java.util.Date", setOf("java.util.Date"), emptySet()))
		assertEquals("Date", shortenTypeText("java.util.Date", emptySet(), setOf("java.util")))
		assertEquals(
			"Flow<Widget>",
			shortenTypeText(
				"kotlinx.coroutines.flow.Flow<com.example.Widget>",
				setOf("kotlinx.coroutines.flow.Flow", "com.example.Widget"),
				emptySet(),
			),
		)
	}

	@Test
	fun `a star import is skipped when a colliding name is imported from elsewhere`() {
		// An explicit import of a different `Date` shadows the star import, so shortening would
		// resolve to the wrong type.
		assertEquals(
			"java.util.Date",
			shortenTypeText("java.util.Date", setOf("com.example.Date"), setOf("java.util")),
		)
		// With nothing colliding, the star import still shortens as before.
		assertEquals(
			"Date",
			shortenTypeText("java.util.Date", emptySet(), setOf("java.util")),
		)
	}

	@Test
	fun `unrenderable type text is recognised`() {
		assertTrue(isUnrenderableTypeText(""))
		assertTrue(isUnrenderableTypeText("kotlin.collections.List<kotlin.String!>"))
		assertTrue(isUnrenderableTypeText("<anonymous object>"))
		assertTrue(isUnrenderableTypeText("ERROR CLASS: unresolved"))
		assertTrue(isUnrenderableTypeText("kotlin.Any & kotlin.Comparable<*>"))
		assertFalse(isUnrenderableTypeText("kotlin.Int"))
	}

	@Test
	fun `Unit type text is recognised qualified and short`() {
		assertTrue(isUnitTypeText("Unit"))
		assertTrue(isUnitTypeText("kotlin.Unit"))
		assertFalse(isUnitTypeText("Int"))
		assertFalse(isUnitTypeText("kotlin.Unit?"))
		assertFalse(isUnitTypeText("MyUnit"))
	}

	@Test
	fun `a rendered Unit retracts both the return and the written type`() {
		// The only way to reach here is the resolved-type check disagreeing with the text about to be
		// written; the text is what lands in the file, so it wins.
		assertEquals(false to null, normalizeExpressionBodyReturn(needsReturn = true, returnTypeText = "Unit"))
		assertEquals(false to null, normalizeExpressionBodyReturn(needsReturn = true, returnTypeText = "kotlin.Unit"))
	}

	@Test
	fun `a non-Unit type keeps the return and the written type`() {
		assertEquals(true to "Int", normalizeExpressionBodyReturn(needsReturn = true, returnTypeText = "Int"))
		assertEquals(true to null, normalizeExpressionBodyReturn(needsReturn = true, returnTypeText = null))
		assertEquals(false to null, normalizeExpressionBodyReturn(needsReturn = false, returnTypeText = null))
	}

	@Test
	fun `a retracted return retracts the written type with it`() {
		// No return means a Unit return, which needs no written type either. The rewrite reads the two
		// independently, so the other pairing would emit `fun f(): Int { val v = ...; expr }`.
		assertEquals(false to null, normalizeExpressionBodyReturn(needsReturn = false, returnTypeText = "Int"))
	}
}
