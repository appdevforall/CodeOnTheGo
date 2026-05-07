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
    /** True if any keyword in [set] is a substring of [text] (case-insensitive). */
    fun anyKeywordIn(text: String, set: Set<String>): Boolean {
        if (text.isEmpty() || set.isEmpty()) return false
        val lower = text.lowercase()
        return set.any { kw -> kw.isNotEmpty() && lower.contains(kw.lowercase()) }
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
                "date", "dob", "d.o.b", "birth", "birthday", "born",
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
