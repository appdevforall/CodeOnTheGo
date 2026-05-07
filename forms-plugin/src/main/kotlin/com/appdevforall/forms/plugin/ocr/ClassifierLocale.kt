package com.appdevforall.forms.plugin.ocr

/**
 * Per-locale keyword tables for the heuristic field classifier.
 *
 * The glyph-level heuristics ("__" run, "☐" checkbox, "/" date mask,
 * paren glyphs, etc.) are mostly script-agnostic — they fire on punctuation
 * and box-drawing characters that appear in printed forms regardless of
 * language. The keyword tables in this class cover the *semantic* hints
 * that need translation: what counts as a date label, a signature label, a
 * group header, etc.
 *
 * The plugin can override [EN] at runtime by loading a JSON file from the
 * plugin assets — call sites construct a [ClassifierLocale] from that JSON
 * and pass it to [HeuristicFieldClassifier]. We don't need that
 * indirection tonight; tonight we ship EN and document the overlay path.
 *
 * NGO-relevant locales (Devanagari, Bengali, Tamil, Amharic, Swahili, Spanish)
 * live in the gap doc — none ship today; ML Kit Text Recognition v2 has
 * dedicated recognizers per script that we'd select before invoking the
 * classifier with the matching locale.
 */
data class ClassifierLocale(
    /** Words that, when present in a label, hint at a [com.appdevforall.forms.plugin.FieldType.DATE]. */
    val dateKeywords: Set<String>,
    /** Words that, when present in a label, hint that the field is a signature (deferred role). */
    val signatureKeywords: Set<String>,
    /** Words/phrases that indicate the line is a section header / group. */
    val groupHeaders: Set<String>,
    /** Words that, when present in a label, hint at a [com.appdevforall.forms.plugin.FieldType.NUMBER]. */
    val numberKeywords: Set<String>,
    /** Words that, when present in a label, hint at long-form text (multi-line). */
    val longTextKeywords: Set<String>,
    /** Tokens / glyphs that mark a field as required (e.g. "*", "(required)"). */
    val requiredMarkers: Set<String>,
) {
    /**
     * True if any keyword in [set] matches a whole token in [text]
     * (case-insensitive).
     *
     * Word-boundary matching: the label is split into tokens on non-word
     * characters, and each keyword must match a complete token (or, for
     * multi-word keywords like "sign here" / "personal information", appear
     * as a substring with whitespace boundaries on both sides).
     *
     * **Why not substring containment?** The forms-benchmark caught false
     * positives where short keywords matched mid-word — e.g. "age" inside
     * "village" classifying "Town / village" as NUMBER (31 cases), or
     * "birth" inside "Place of birth" classifying it as DATE (4 cases).
     * Word-boundary matching kills those without losing real positives.
     *
     * **Required-marker exception**: requiredMarkers like `*` and `(required)`
     * are matched with substring containment (handled by [anyMarkerIn])
     * because they're punctuation/decoration, not words.
     */
    fun anyKeywordIn(text: String, set: Set<String>): Boolean {
        if (text.isEmpty() || set.isEmpty()) return false
        val lower = text.lowercase()
        // Split on non-word characters; keep numeric tokens too in case a
        // keyword like "no." is checked elsewhere.
        val tokens = lower.split(Regex("\\W+")).filter { it.isNotEmpty() }.toSet()
        for (kw in set) {
            if (kw.isEmpty()) continue
            val kwLower = kw.lowercase()
            // Multi-word keyword: regex with word boundaries on both ends,
            // collapsing any internal whitespace in the keyword to `\s+`
            // so OCR jitter ("sign  here") still matches.
            if (kwLower.contains(' ')) {
                val pattern = kwLower.split(Regex("\\s+"))
                    .filter { it.isNotEmpty() }
                    .joinToString("\\s+") { Regex.escape(it) }
                if (Regex("(?<!\\w)$pattern(?!\\w)").containsMatchIn(lower)) return true
                continue
            }
            // Single-word keyword: must match a complete token.
            if (kwLower in tokens) return true
            // Some keywords contain non-word chars themselves (e.g. "no.",
            // "d.o.b") — `\W+` split would shred them. Detect by testing
            // for any non-word character in the keyword and falling back to
            // a regex-with-boundaries match.
            if (kwLower.any { !it.isLetterOrDigit() && it != '_' }) {
                if (Regex("(?<!\\w)${Regex.escape(kwLower)}(?!\\w)").containsMatchIn(lower)) {
                    return true
                }
            }
        }
        return false
    }

    /**
     * True if any marker in [set] appears as a substring of [text]
     * (case-insensitive). Used for required-markers like `*` / `(required)`
     * which aren't words and shouldn't be word-boundary-matched.
     */
    fun anyMarkerIn(text: String, set: Set<String>): Boolean {
        if (text.isEmpty() || set.isEmpty()) return false
        val lower = text.lowercase()
        return set.any { m -> m.isNotEmpty() && lower.contains(m.lowercase()) }
    }

    companion object {
        /**
         * English defaults. Conservative: keywords are ones that almost
         * never appear in non-date-y / non-group-y prose. We'd rather miss
         * a field type assignment and let the user fix it in step 2 than
         * mis-classify a generic text field as a date.
         */
        val EN = ClassifierLocale(
            dateKeywords = setOf(
                // Note: "birth" alone is intentionally NOT here. "Place of
                // birth" is a TEXT field (location), not a date — the
                // forms-benchmark caught 4 false positives. "Date of birth"
                // still matches via "date"; "Birthday" via "birthday".
                "date", "dob", "d.o.b", "birthday", "born",
                "issued", "expiry", "expires", "expiration",
            ),
            signatureKeywords = setOf(
                "signature", "sign here", "signed", "applicant signature",
                "sign", // bare "sign" as a label, e.g. "Sign:"
            ),
            groupHeaders = setOf(
                "section", "group", "personal info", "personal information",
                "address", "contact", "contact info", "contact information",
                "household", "demographics", "consent", "intake",
            ),
            numberKeywords = setOf(
                "age", "year", "years", "phone", "telephone", "mobile",
                "amount", "total", "count", "number", "no.", "qty",
                "weight", "height", "income",
            ),
            longTextKeywords = setOf(
                "comments", "notes", "remarks", "description",
                "details", "history", "explain",
            ),
            requiredMarkers = setOf(
                "*", "(required)", "[required]", "required",
            ),
        )
    }
}
