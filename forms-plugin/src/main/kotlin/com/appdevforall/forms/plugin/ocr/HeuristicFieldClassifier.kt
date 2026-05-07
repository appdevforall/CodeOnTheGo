package com.appdevforall.forms.plugin.ocr

import com.appdevforall.forms.plugin.FieldType
import com.appdevforall.forms.plugin.FormField
import com.appdevforall.forms.plugin.wizard.WizardViewModel

/**
 * Classifies an [OcrResult] into a list of [FormField] suggestions for the
 * Forms plugin's wizard.
 *
 * This replaces the wrong-domain `cv-image-to-xml/` integration for paper
 * forms (see `docs/notes/form-extraction-state-of-affairs.md`). The
 * existing CV pipeline detects 13 Android UI widgets — useful for "screenshot
 * of an app -> layout xml" but not for "phone photo of a paper intake form".
 *
 * **Patterns recognised** (English; locale-pluggable via [ClassifierLocale]):
 *
 * | Pattern | -> FieldType |
 * |---|---|
 * | `Label:` followed by underscore run on the same line | TEXT |
 * | `Label:` followed by date-mask `__/__/____` | DATE |
 * | `Label:` whose label text matches a date keyword | DATE |
 * | `Label:` whose label text matches a number keyword | NUMBER |
 * | `Label:` whose label text matches a long-text keyword | LONGTEXT |
 * | Checkbox glyph (`☐ ☑ ⬜ □ ■` / `[ ]` / `[x]`) followed by label | CHECKBOX |
 * | Radio glyph (`○ ◯ ⚪` / `( )`) followed by label | CHECKBOX (radio role pending schema) |
 * | Multi-line ruled area (3+ consecutive underscore-only lines) | LONGTEXT |
 * | All-caps line, prominent font, no trailing colon | (header — not emitted) |
 * | Section / group header line | (group — not emitted) |
 *
 * **Patterns explicitly NOT a field** — pure paragraphs, decorative all-caps
 * headers without colons, lines that are only labels with no input shape
 * after them. The classifier prefers under-detection: every false negative
 * is a field the user adds in step 2 of the wizard, but every false
 * positive is a junk field they have to delete.
 *
 * **Header / Group emission deferred.** [FormField] doesn't have HEADER /
 * GROUP roles yet — that's the same R3 deferral the original
 * [com.appdevforall.forms.plugin.wizard.CvLayoutParser] does. The classifier
 * detects them but drops them on the floor for now. When the schema gains
 * those roles we'll wire them through.
 */
class HeuristicFieldClassifier(
    private val locale: ClassifierLocale = ClassifierLocale.EN,
) {

    /**
     * Classify an OCR result into form-field suggestions.
     *
     * Order is preserved from the OCR output (top-to-bottom by block, then
     * line) so the wizard step 2 review surface lists fields in the same
     * order the user sees them on the paper form.
     */
    fun classify(ocr: OcrResult): List<FormField> {
        val allLines = ocr.allLines()
        if (allLines.isEmpty()) return emptyList()

        val fields = mutableListOf<FormField>()

        // First, group consecutive underscore-only lines as a multi-line
        // ruled area attached to the label of the line above it. We
        // detect the multi-line case before the line-by-line walk so we
        // can skip the absorbed underscore lines.
        val absorbedLineIndices = mutableSetOf<Int>()
        for (i in allLines.indices) {
            if (i in absorbedLineIndices) continue
            if (!isLineLabelOnly(allLines[i])) continue
            // Look ahead: do the next 2+ lines look like underscore runs?
            var j = i + 1
            var underscoreLineCount = 0
            while (j < allLines.size && Heuristics.isUnderscoreRun(allLines[j].text)) {
                underscoreLineCount++
                j++
            }
            if (underscoreLineCount >= 2) {
                val label = Heuristics.extractLabel(allLines[i].text) ?: continue
                fields += newField(label, FieldType.LONGTEXT, locale)
                // Mark the label and the underscore lines as absorbed
                // so the main loop below doesn't re-process them.
                absorbedLineIndices += i
                for (k in (i + 1) until j) absorbedLineIndices += k
            }
        }

        // Main pass: classify each remaining line.
        for ((index, line) in allLines.withIndex()) {
            if (index in absorbedLineIndices) continue
            classifyLine(line, allLines)?.let { fields += it }
        }

        return fields
    }

    // -- per-line classification ----------------------------------------

    private fun classifyLine(line: OcrLine, allLines: List<OcrLine>): FormField? {
        val text = line.text.trim()
        if (text.isEmpty()) return null

        // 1. Checkbox-shaped lines -> CHECKBOX with the label that follows
        //    the glyph. We look at the elements (more reliable than
        //    splitting the joined line text).
        classifyCheckboxLine(line)?.let { return it }
        classifyRadioLine(line)?.let { return it }

        // 2. Line with a trailing colon (i.e. "Label:" or "Label: <stuff>")
        //    is the dominant input-field shape on paper forms. The "stuff"
        //    after the colon (or its absence) tells us the type.
        classifyLabelColonLine(line)?.let { return it }

        // 3. All-caps prominent line -> header (not emitted yet, see KDoc)
        if (Heuristics.isAllCaps(text) &&
            Heuristics.isLargeFontRelative(line, allLines) &&
            !text.endsWith(":")
        ) {
            return null
        }

        // 4. Group-header keyword line -> group (not emitted yet)
        if (locale.anyKeywordIn(text, locale.groupHeaders) && text.length < 40) {
            return null
        }

        // No pattern matched -> not a field. The classifier prefers under-
        // detection over noisy false positives.
        return null
    }

    private fun classifyCheckboxLine(line: OcrLine): FormField? {
        val elements = line.elements
        if (elements.isEmpty()) return null

        // Multi-checkbox-on-one-line case: "Sex: ☐ M ☐ F" or
        // "Gender: ☐ Male ☐ Female ☐ Other". Two or more checkbox glyphs on
        // a single line indicate a single-choice / multi-choice group with
        // a prefix label (text before the first glyph) and per-option texts
        // (text between glyphs). We emit ONE field per line — losing the
        // per-option semantics is a known limitation until the schema gains
        // a CHOICE / RADIO role. The important fix here is to preserve the
        // PREFIX label ("Sex") rather than emitting "M ☐ F" as the label.
        //
        // TODO: emit as CHOICE / RADIO with explicit options once
        // FieldType supports it (see FormSchema.kt — currently only
        // TEXT / LONGTEXT / NUMBER / DATE / CHECKBOX).
        val glyphIndices = elements.mapIndexedNotNull { i, e ->
            if (Heuristics.isCheckboxGlyph(e.text)) i else null
        }
        if (glyphIndices.size >= 2) {
            return classifyMultiCheckboxLine(elements, glyphIndices)
        }

        // Find the first checkbox glyph; the rest of the line is the label.
        val glyphIndex = elements.indexOfFirst { Heuristics.isCheckboxGlyph(it.text) }
        if (glyphIndex < 0) {
            // Fallback: ML Kit sometimes joins glyph + label into a single
            // element ("☐ Pregnant?"). Try the joined-text approach too.
            val joinedFirstChar = line.text.trim().firstOrNull()?.toString().orEmpty()
            if (!Heuristics.isCheckboxGlyph(joinedFirstChar)) return null
            val labelText = line.text.trim().drop(1).trim()
            val label = Heuristics.extractLabel(labelText) ?: return null
            return newField(label, FieldType.CHECKBOX, locale)
        }
        val labelElems = elements.drop(glyphIndex + 1)
        if (labelElems.isEmpty()) return null
        val labelText = labelElems.joinToString(" ") { it.text }
        val label = Heuristics.extractLabel(labelText) ?: return null
        return newField(label, FieldType.CHECKBOX, locale)
    }

    /**
     * Handle a line with 2+ checkbox glyphs ("Sex: ☐ M ☐ F").
     *
     * Strategy:
     *   - If there's a prefix (elements before the first glyph) ending in
     *     a colon-bearing token (e.g. "Sex:"), use that prefix as the
     *     field label.
     *   - Otherwise, fall back to the per-option labels joined ("A / B"),
     *     which at least preserves the option text instead of dropping it.
     *
     * Returns ONE FormField with type CHECKBOX. (See KDoc on
     * [classifyCheckboxLine] for why we don't emit RADIO yet.)
     */
    private fun classifyMultiCheckboxLine(
        elements: List<OcrElement>,
        glyphIndices: List<Int>,
    ): FormField? {
        val firstGlyphIdx = glyphIndices.first()
        val prefixElems = elements.take(firstGlyphIdx)
        val prefixText = prefixElems.joinToString(" ") { it.text }.trim()

        // Extract per-option texts: between consecutive glyphs, plus from
        // the last glyph to end-of-line.
        val options = mutableListOf<String>()
        for (k in glyphIndices.indices) {
            val start = glyphIndices[k] + 1
            val end = if (k + 1 < glyphIndices.size) glyphIndices[k + 1] else elements.size
            val optionText = elements.subList(start, end)
                .joinToString(" ") { it.text }
                .trim()
            if (optionText.isNotEmpty()) options += optionText
        }

        val labelFromPrefix = if (prefixText.isNotEmpty()) {
            // Heuristic: a "real" group prefix ends with a colon (e.g.
            // "Sex:", "Gender:") or is short alphabetic prose. We require
            // either a colon OR at least one alphabetic char to avoid
            // turning a bullet glyph at line start into a label.
            Heuristics.extractLabel(prefixText)
                ?.takeIf { it.any { c -> c.isLetter() } }
        } else null

        val label = labelFromPrefix
            ?: options.takeIf { it.isNotEmpty() }?.joinToString(" / ")
            ?: return null
        return newField(label, FieldType.CHECKBOX, locale, rawLabelText = prefixText.ifEmpty { label })
    }

    private fun classifyRadioLine(line: OcrLine): FormField? {
        val elements = line.elements
        if (elements.isEmpty()) return null

        // Multi-radio-on-one-line case mirrors the multi-checkbox path:
        // "○ Citizen   ○ Non-citizen" should produce ONE field whose label
        // captures the group, not "Citizen ○ Non-citizen". Since the schema
        // doesn't have RADIO/CHOICE yet (FormSchema.kt), we emit CHECKBOX
        // and join the options into the label. TODO: switch to RADIO once
        // FieldType supports it.
        val radioIndices = elements.mapIndexedNotNull { i, e ->
            if (Heuristics.isRadioGlyph(e.text)) i else null
        }
        if (radioIndices.size >= 2) {
            return classifyMultiRadioLine(elements, radioIndices)
        }

        val glyphIndex = elements.indexOfFirst { Heuristics.isRadioGlyph(it.text) }
        if (glyphIndex < 0) {
            val joinedFirstChar = line.text.trim().firstOrNull()?.toString().orEmpty()
            if (!Heuristics.isRadioGlyph(joinedFirstChar)) return null
            val labelText = line.text.trim().drop(1).trim()
            val label = Heuristics.extractLabel(labelText) ?: return null
            // Radio role -> CHECKBOX until the schema gains a radio role.
            return newField(label, FieldType.CHECKBOX, locale)
        }
        val labelElems = elements.drop(glyphIndex + 1)
        if (labelElems.isEmpty()) return null
        val labelText = labelElems.joinToString(" ") { it.text }
        val label = Heuristics.extractLabel(labelText) ?: return null
        return newField(label, FieldType.CHECKBOX, locale)
    }

    /**
     * Multi-radio-line analogue of [classifyMultiCheckboxLine]. See KDoc on
     * the checkbox version for the strategy.
     */
    private fun classifyMultiRadioLine(
        elements: List<OcrElement>,
        radioIndices: List<Int>,
    ): FormField? {
        val firstIdx = radioIndices.first()
        val prefixElems = elements.take(firstIdx)
        val prefixText = prefixElems.joinToString(" ") { it.text }.trim()

        val options = mutableListOf<String>()
        for (k in radioIndices.indices) {
            val start = radioIndices[k] + 1
            val end = if (k + 1 < radioIndices.size) radioIndices[k + 1] else elements.size
            val optionText = elements.subList(start, end)
                .joinToString(" ") { it.text }
                .trim()
            if (optionText.isNotEmpty()) options += optionText
        }

        val labelFromPrefix = if (prefixText.isNotEmpty()) {
            Heuristics.extractLabel(prefixText)
                ?.takeIf { it.any { c -> c.isLetter() } }
        } else null

        val label = labelFromPrefix
            ?: options.takeIf { it.isNotEmpty() }?.joinToString(" / ")
            ?: return null
        return newField(label, FieldType.CHECKBOX, locale, rawLabelText = prefixText.ifEmpty { label })
    }

    private fun classifyLabelColonLine(line: OcrLine): FormField? {
        val text = line.text.trim()
        // Two shapes get here:
        //   "Name: ____________"  -> single-line text, possibly typed
        //   "DOB: __/__/____"     -> date mask
        //   "Notes:"              -> trailing-colon-only label (no mask)
        // Only count it as a label-colon line if there is a colon.
        if (':' !in text) return null

        val (rawLabel, rest) = text.split(':', limit = 2).let {
            it[0].trim() to (it.getOrNull(1)?.trim().orEmpty())
        }
        val label = Heuristics.extractLabel(rawLabel) ?: return null

        // Skip lines that look like "Section: Personal Information" — the
        // value side is plain prose, not an underscore/mask. Heuristic:
        // the value side has alphabetic words AND no underscores AND no
        // date separators -> probably a heading or an inline value, not a
        // field.
        if (rest.isNotEmpty() && looksLikeProseValue(rest)) {
            return null
        }

        // Pick the type:
        // 1. Date label keyword wins ("Date of birth" -> DATE)
        // 2. Date-shaped value mask wins ("__/__/____" -> DATE)
        // 3. Long-text label keyword
        // 4. Number label keyword
        // 5. Default TEXT
        val type = when {
            locale.anyKeywordIn(label, locale.dateKeywords) -> FieldType.DATE
            rest.isNotEmpty() && Heuristics.isDateMask(rest) -> FieldType.DATE
            locale.anyKeywordIn(label, locale.longTextKeywords) -> FieldType.LONGTEXT
            locale.anyKeywordIn(label, locale.numberKeywords) -> FieldType.NUMBER
            locale.anyKeywordIn(label, locale.signatureKeywords) -> {
                // Signature role isn't in the schema yet — surface it as
                // TEXT so the wizard at least lets the user delete it
                // rather than silently dropping the signature line.
                FieldType.TEXT
            }
            else -> FieldType.TEXT
        }

        return newField(label, type, locale, rawLabelText = rawLabel)
    }

    // -- helpers --------------------------------------------------------

    /**
     * A line is "label only" when it's a short token followed by a colon
     * with nothing meaningful after it — the kind of thing a multi-line
     * ruled area (LONGTEXT) lives under.
     */
    private fun isLineLabelOnly(line: OcrLine): Boolean {
        val text = line.text.trim()
        if (':' !in text) return false
        val (rawLabel, rest) = text.split(':', limit = 2).let {
            it[0].trim() to (it.getOrNull(1)?.trim().orEmpty())
        }
        if (rawLabel.isEmpty()) return false
        // "Notes:" qualifies. "Notes: see attached" doesn't.
        return rest.isEmpty()
    }

    private fun looksLikeProseValue(text: String): Boolean {
        if (text.isEmpty()) return false
        if ('_' in text) return false
        if (text.contains(Regex("[/.\\-]\\s*\\d"))) return false // looks date-y
        if (text.contains(Regex("\\d"))) return false // value with digits != prose for our purposes
        // Multiple alphabetic words -> prose (e.g. "Personal Information")
        val words = text.split(Regex("\\s+")).filter { it.any { c -> c.isLetter() } }
        return words.size >= 2
    }

    private fun newField(
        label: String,
        type: FieldType,
        locale: ClassifierLocale,
        rawLabelText: String = label,
    ): FormField {
        // Detect required-marker on the original (un-cleaned) label text.
        // Use anyMarkerIn (substring containment) — required markers like
        // `*` and `(required)` are punctuation/decoration, not whole words,
        // so word-boundary matching would miss them.
        val required = locale.anyMarkerIn(rawLabelText, locale.requiredMarkers)
        return WizardViewModel.newField(label = label, type = type)
            .copy(required = required, confidence = DEFAULT_CONFIDENCE)
    }

    companion object {
        /**
         * Default confidence for heuristic-classified fields. Heuristics
         * don't produce calibrated probabilities — we use a flat value so
         * the wizard knows the field came from auto-detection (so it can
         * be styled differently in step 2) without claiming false
         * precision.
         */
        const val DEFAULT_CONFIDENCE: Double = 0.70
    }
}
