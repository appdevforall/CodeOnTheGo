package com.appdevforall.forms.plugin.ocr

/**
 * Small, individually testable heuristic primitives used by
 * [HeuristicFieldClassifier]. Each function answers exactly one question
 * about an OCR fragment so it can be unit-tested in isolation.
 *
 * These are intentionally shallow — they look at glyphs and string shapes,
 * not semantics. The classifier composes them with the locale keyword
 * tables to produce a [com.appdevforall.forms.plugin.FormField].
 *
 * **Why not regexes?** Most of these *are* regex-shaped, and we use regex
 * inside. The wrapper functions exist so the classifier reads as
 * `isCheckboxGlyph(token)` rather than `CHECKBOX_REGEX.matches(token)` —
 * the abstraction makes the test names readable and gives one place to
 * maintain the glyph alphabets as we discover OCR misreads in the wild.
 */
internal object Heuristics {

    // -- glyph alphabets -------------------------------------------------

    /**
     * Checkbox glyphs commonly seen in printed forms (and in OCR output of
     * those). ML Kit recognizes the Unicode shapes; the ASCII square
     * brackets variants `[ ]` / `[x]` show up when a form was photocopied
     * and the renderer flattened the box character.
     *
     * Single-glyph cells only — see [isCheckboxGlyph] for the predicate.
     * We don't include `0` or `o` since those have huge false-positive risk.
     */
    private val CHECKBOX_GLYPHS: Set<String> = setOf(
        "☐", // ☐ BALLOT BOX
        "☑", // ☑ BALLOT BOX WITH CHECK
        "☒", // ☒ BALLOT BOX WITH X
        "■", // ■ BLACK SQUARE
        "□", // □ WHITE SQUARE
        "▪", // ▪ BLACK SMALL SQUARE
        "▫", // ▫ WHITE SMALL SQUARE
        "⬛", // ⬛ BLACK LARGE SQUARE
        "⬜", // ⬜ WHITE LARGE SQUARE
    )

    /**
     * Radio-button glyphs. We keep these separate from checkbox glyphs so
     * the classifier can suggest a RadioGroup / single-choice control
     * (deferred field role — currently mapped to CHECKBOX in [FormField]
     * since the schema doesn't have a radio type yet).
     */
    private val RADIO_GLYPHS: Set<String> = setOf(
        "○", // ○ WHITE CIRCLE
        "●", // ● BLACK CIRCLE
        "◯", // ◯ LARGE CIRCLE
        "⚪", // ⚪ MEDIUM WHITE CIRCLE
        "⚫", // ⚫ MEDIUM BLACK CIRCLE
    )

    /** Pure run of three or more underscores — "Name: _______" style. */
    private val UNDERSCORE_RUN_REGEX = Regex("^_{3,}$")

    /**
     * Date mask: `__/__/____`, `MM/DD/YYYY`, `DD-MM-YY`, `YYYY-MM-DD`,
     * `__.__.____`. We accept letters M/D/Y and underscores as digit
     * placeholders. A separator character (`/` `-` `.`) is required to
     * avoid matching plain underscore runs.
     */
    private val DATE_MASK_REGEX = Regex(
        // group 1: 2-4 chars of digits, underscores, or D/M/Y placeholders
        // group 2: separator
        // matched at least twice -> three groups separated by separators
        "^[\\d_DMYdmy]{1,4}([/.\\-])[\\d_DMYdmy]{1,4}\\1[\\d_DMYdmy]{1,4}$"
    )

    /**
     * Looser date pattern that allows surrounding whitespace and matches
     * even when ML Kit splits the mask into spaced elements
     * ("__ / __ / ____"). We strip whitespace before testing.
     */
    private val DATE_MASK_STRIPPED_REGEX = DATE_MASK_REGEX

    /** Plain slash mask without the digit-placeholder requirement. */
    private val SLASH_MASK_REGEX = Regex("^[\\w_]+/[\\w_]+(/[\\w_]+)?$")

    // -- predicates ------------------------------------------------------

    fun isUnderscoreRun(text: String): Boolean =
        UNDERSCORE_RUN_REGEX.matches(text.trim())

    fun isCheckboxGlyph(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed in CHECKBOX_GLYPHS) return true
        // ASCII fallbacks: "[ ]", "[x]", "[X]", "[v]" (check-mark)
        return trimmed.matches(Regex("^\\[[ xXvV✓✔]?]$"))
    }

    fun isRadioGlyph(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed in RADIO_GLYPHS) return true
        // ASCII fallback: "( )", "(o)", "(x)" — paren-and-mark
        return trimmed.matches(Regex("^\\([ oOxX•]?\\)$"))
    }

    fun isDateMask(text: String): Boolean {
        // Strip whitespace inside the token in case ML Kit kept "__ / __ / ____"
        // as a single line element.
        val collapsed = text.trim().replace(Regex("\\s+"), "")
        return DATE_MASK_STRIPPED_REGEX.matches(collapsed)
    }

    fun isSlashMask(text: String): Boolean =
        SLASH_MASK_REGEX.matches(text.trim())

    /**
     * A line is "all caps" when its alphabetic characters are all uppercase
     * AND there is at least one alphabetic character. Lines that are only
     * digits or punctuation are not considered all-caps headers.
     */
    fun isAllCaps(text: String): Boolean {
        val alphas = text.filter { it.isLetter() }
        if (alphas.isEmpty()) return false
        return alphas.all { it.isUpperCase() }
    }

    /**
     * Heuristic for "this line uses a noticeably larger font than the
     * average line on the page". We approximate font size by line bounds
     * height. A line is considered large when its height is at least
     * [factor] times the median height of all lines on the page.
     *
     * Returns false if [allLines] is empty or has no measurable lines.
     */
    fun isLargeFontRelative(
        line: OcrLine,
        allLines: List<OcrLine>,
        factor: Double = 1.4,
    ): Boolean {
        if (allLines.isEmpty()) return false
        val heights = allLines.map { it.bounds.height }.filter { it > 0 }.sorted()
        if (heights.isEmpty()) return false
        val median = heights[heights.size / 2]
        if (median == 0) return false
        return line.bounds.height >= (median * factor)
    }

    /**
     * Pull a clean label from text that may have a trailing colon, an
     * asterisk required-marker, or a "(required)" suffix.
     *
     * Returns null if there's nothing left after cleanup — that signals
     * "this isn't a label, drop it".
     */
    fun extractLabel(text: String): String? {
        // Remove trailing colon and surrounding whitespace.
        var working = text.trim()
        // Strip "(required)" / "[required]" / trailing "required" suffix
        working = working.replace(
            Regex("\\s*[\\(\\[]\\s*required\\s*[\\)\\]]\\s*$", RegexOption.IGNORE_CASE),
            "",
        ).trim()
        // Strip a trailing colon.
        if (working.endsWith(":")) working = working.dropLast(1).trim()
        // Strip a single trailing asterisk if it's a required-marker
        // ("Name *" -> "Name"). Don't strip if the entire text is just "*".
        if (working.length > 1 && working.endsWith("*")) {
            working = working.dropLast(1).trim()
        }
        return working.takeIf { it.isNotEmpty() }
    }

    /**
     * Two elements are on the same line when their vertical bounds overlap
     * substantially. Used to attach a mask / glyph to its label.
     *
     * The threshold is intentionally generous (40%): OCR can clip
     * descenders / ascenders so two elements on the same printed line
     * sometimes have only ~50% overlap.
     */
    fun areOnSameLine(a: Rect, b: Rect, threshold: Double = 0.40): Boolean =
        a.verticalOverlapFraction(b) >= threshold

    /**
     * Elements horizontally to the right of [label] on its line, sorted
     * left-to-right. Used to find the underscore run / date mask attached
     * to "Name: ____".
     */
    fun rightOfLabel(label: OcrElement, line: OcrLine): List<OcrElement> {
        if (line.elements.isEmpty()) return emptyList()
        return line.elements
            .filter { it.bounds.left >= label.bounds.right }
            .sortedBy { it.bounds.left }
    }
}
