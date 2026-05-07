package com.appdevforall.forms.plugin.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the [Heuristics] primitives.
 *
 * Each primitive answers exactly one question — these tests pin the
 * answers for the obvious cases plus the edge cases we already know about
 * from the form-extraction-state-of-affairs notes. A primitive that needs
 * more than ~10 lines of test setup probably wants to be split into two.
 */
class HeuristicsTest {

    // -- isUnderscoreRun ------------------------------------------------

    @Test
    fun underscoreRunDetectsRuns() {
        assertTrue(Heuristics.isUnderscoreRun("___"))
        assertTrue(Heuristics.isUnderscoreRun("________________"))
        assertTrue(Heuristics.isUnderscoreRun("  ____  "))
    }

    @Test
    fun underscoreRunRejectsShortAndMixed() {
        assertFalse(Heuristics.isUnderscoreRun("__"))      // need 3+
        assertFalse(Heuristics.isUnderscoreRun(""))
        assertFalse(Heuristics.isUnderscoreRun("___abc"))
        assertFalse(Heuristics.isUnderscoreRun("a___b"))
    }

    // -- isCheckboxGlyph ------------------------------------------------

    @Test
    fun checkboxGlyphDetectsUnicodeBoxes() {
        assertTrue(Heuristics.isCheckboxGlyph("☐"))
        assertTrue(Heuristics.isCheckboxGlyph("☑"))
        assertTrue(Heuristics.isCheckboxGlyph("☒"))
        assertTrue(Heuristics.isCheckboxGlyph("⬜"))
        assertTrue(Heuristics.isCheckboxGlyph("□"))
    }

    @Test
    fun checkboxGlyphDetectsAsciiBrackets() {
        assertTrue(Heuristics.isCheckboxGlyph("[ ]"))
        assertTrue(Heuristics.isCheckboxGlyph("[x]"))
        assertTrue(Heuristics.isCheckboxGlyph("[X]"))
        assertTrue(Heuristics.isCheckboxGlyph("[v]"))
        assertTrue(Heuristics.isCheckboxGlyph("[]"))
    }

    @Test
    fun checkboxGlyphRejectsNonGlyphs() {
        assertFalse(Heuristics.isCheckboxGlyph(""))
        assertFalse(Heuristics.isCheckboxGlyph("Pregnant?"))
        assertFalse(Heuristics.isCheckboxGlyph("☐ Pregnant?")) // glyph + label != glyph alone
        assertFalse(Heuristics.isCheckboxGlyph("(x)"))         // that's a radio
        assertFalse(Heuristics.isCheckboxGlyph("0"))
    }

    // -- isRadioGlyph ---------------------------------------------------

    @Test
    fun radioGlyphDetectsCircles() {
        assertTrue(Heuristics.isRadioGlyph("○"))
        assertTrue(Heuristics.isRadioGlyph("●"))
        assertTrue(Heuristics.isRadioGlyph("◯"))
    }

    @Test
    fun radioGlyphDetectsAsciiParens() {
        assertTrue(Heuristics.isRadioGlyph("( )"))
        assertTrue(Heuristics.isRadioGlyph("(o)"))
        assertTrue(Heuristics.isRadioGlyph("(x)"))
    }

    @Test
    fun radioGlyphRejectsNonRadio() {
        assertFalse(Heuristics.isRadioGlyph("☐"))      // checkbox
        assertFalse(Heuristics.isRadioGlyph("(yes)")) // too long inside parens
        assertFalse(Heuristics.isRadioGlyph(""))
    }

    // -- isDateMask -----------------------------------------------------

    @Test
    fun dateMaskDetectsCommonShapes() {
        assertTrue(Heuristics.isDateMask("__/__/____"))
        assertTrue(Heuristics.isDateMask("MM/DD/YYYY"))
        assertTrue(Heuristics.isDateMask("DD-MM-YY"))
        assertTrue(Heuristics.isDateMask("YYYY-MM-DD"))
        assertTrue(Heuristics.isDateMask("__ / __ / ____")) // ML Kit may keep spaces
        assertTrue(Heuristics.isDateMask("__.__.____"))
    }

    @Test
    fun dateMaskRejectsNonDates() {
        assertFalse(Heuristics.isDateMask(""))
        assertFalse(Heuristics.isDateMask("Name"))
        assertFalse(Heuristics.isDateMask("__________"))   // pure underscore run
        assertFalse(Heuristics.isDateMask("hello/world")) // slashes but words
    }

    // -- isAllCaps ------------------------------------------------------

    @Test
    fun allCapsDetectsUppercaseLines() {
        assertTrue(Heuristics.isAllCaps("VACCINATION CAMP — INTAKE FORM"))
        assertTrue(Heuristics.isAllCaps("PERSONAL INFORMATION"))
        assertTrue(Heuristics.isAllCaps("YES"))
    }

    @Test
    fun allCapsRejectsMixedAndDigits() {
        assertFalse(Heuristics.isAllCaps("Name"))
        assertFalse(Heuristics.isAllCaps("12345"))
        assertFalse(Heuristics.isAllCaps(""))
        assertFalse(Heuristics.isAllCaps("---"))
        assertFalse(Heuristics.isAllCaps("ALL caps mixed"))
    }

    // -- isLargeFontRelative --------------------------------------------

    @Test
    fun largeFontDetectsHeightOutlier() {
        val small1 = lineOf("body 1", height = 20)
        val small2 = lineOf("body 2", height = 22)
        val small3 = lineOf("body 3", height = 18)
        val header = lineOf("HEADER", height = 40)
        val all = listOf(small1, small2, small3, header)
        assertTrue(Heuristics.isLargeFontRelative(header, all))
        assertFalse(Heuristics.isLargeFontRelative(small1, all))
    }

    @Test
    fun largeFontReturnsFalseOnEmptyInput() {
        val any = lineOf("anything", height = 99)
        assertFalse(Heuristics.isLargeFontRelative(any, emptyList()))
    }

    // -- extractLabel ---------------------------------------------------

    @Test
    fun extractLabelStripsTrailingColon() {
        assertEquals("Name", Heuristics.extractLabel("Name:"))
        assertEquals("Date of birth", Heuristics.extractLabel("Date of birth:"))
        assertEquals("Name", Heuristics.extractLabel("  Name :  "))
    }

    @Test
    fun extractLabelStripsRequiredSuffix() {
        assertEquals("Name", Heuristics.extractLabel("Name (required)"))
        assertEquals("Email", Heuristics.extractLabel("Email [Required]"))
        assertEquals("Phone", Heuristics.extractLabel("Phone *"))
        assertEquals("Phone", Heuristics.extractLabel("Phone *:")) // colon then asterisk
    }

    @Test
    fun extractLabelReturnsNullForBlank() {
        assertNull(Heuristics.extractLabel(""))
        assertNull(Heuristics.extractLabel(":"))
        assertNull(Heuristics.extractLabel("   "))
    }

    // -- areOnSameLine --------------------------------------------------

    @Test
    fun sameLineWhenVerticalOverlapIsHigh() {
        val a = Rect(0, 0, 100, 20)
        val b = Rect(120, 2, 220, 22)
        assertTrue(Heuristics.areOnSameLine(a, b))
    }

    @Test
    fun differentLineWhenStackedVertically() {
        val a = Rect(0, 0, 100, 20)
        val b = Rect(0, 30, 100, 50)
        assertFalse(Heuristics.areOnSameLine(a, b))
    }

    // -- rightOfLabel ---------------------------------------------------

    @Test
    fun rightOfLabelReturnsElementsAfterLabel() {
        val line = lineOf("Name: ____________")
        val labelElem = line.elements.first { it.text.startsWith("Name") }
        val right = Heuristics.rightOfLabel(labelElem, line)
        // Underscore run should be the only element to the right
        assertEquals(1, right.size)
        assertEquals("____________", right[0].text)
    }

    @Test
    fun rightOfLabelEmptyWhenLabelIsLast() {
        val line = lineOf("Notes:")
        val labelElem = line.elements.last()
        val right = Heuristics.rightOfLabel(labelElem, line)
        assertTrue(right.isEmpty())
    }

    // -- ClassifierLocale.anyKeywordIn / anyMarkerIn --------------------

    @Test
    fun anyKeywordInMatchesWholeTokensOnly() {
        val locale = ClassifierLocale.EN
        // "age" must match "Age" (whole token), not "village" (substring).
        assertTrue(locale.anyKeywordIn("Age", locale.numberKeywords))
        assertFalse(locale.anyKeywordIn("village", locale.numberKeywords))
        assertFalse(locale.anyKeywordIn("Town / village", locale.numberKeywords))
    }

    @Test
    fun anyKeywordInIsCaseInsensitive() {
        val locale = ClassifierLocale.EN
        assertTrue(locale.anyKeywordIn("AGE", locale.numberKeywords))
        assertTrue(locale.anyKeywordIn("age", locale.numberKeywords))
        assertTrue(locale.anyKeywordIn("DATE", locale.dateKeywords))
    }

    @Test
    fun anyKeywordInAcceptsKeywordsWithPunctuation() {
        // "no." and "d.o.b" contain non-word characters in the keyword
        // itself; word-boundary regex fallback must still match them.
        val locale = ClassifierLocale.EN
        assertTrue(locale.anyKeywordIn("Tracking no.", locale.numberKeywords))
        assertTrue(locale.anyKeywordIn("Patient D.O.B.", locale.dateKeywords))
    }

    @Test
    fun anyKeywordInHandlesMultiWordKeywords() {
        // "personal information" must match as a whole phrase, not via
        // its individual words.
        val locale = ClassifierLocale.EN
        assertTrue(locale.anyKeywordIn("Personal Information", locale.groupHeaders))
        assertTrue(locale.anyKeywordIn("Section: Personal Information", locale.groupHeaders))
        // "personal" alone shouldn't match the phrase keyword.
        assertFalse(
            ClassifierLocale(
                dateKeywords = emptySet(),
                signatureKeywords = emptySet(),
                groupHeaders = setOf("personal information"),
                numberKeywords = emptySet(),
                longTextKeywords = emptySet(),
                requiredMarkers = emptySet(),
            ).anyKeywordIn("personal", setOf("personal information"))
        )
    }

    @Test
    fun anyMarkerInUsesSubstringContainment() {
        // Required markers like "*" and "(required)" are punctuation /
        // decoration; they must match as substrings, not as whole tokens.
        val locale = ClassifierLocale.EN
        assertTrue(locale.anyMarkerIn("Name *", locale.requiredMarkers))
        assertTrue(locale.anyMarkerIn("Email (required)", locale.requiredMarkers))
        assertFalse(locale.anyMarkerIn("Name", locale.requiredMarkers))
    }
}
