package org.appdevforall.codeonthego.computervision.utils

object TextCleaner {

    private val nonAlphanumericRegex = Regex("[^a-zA-Z0-9 ]")
    private val leadingMarkerRegex = Regex("^[\\[\\]()●○□☑✓-]+\\s*")
    private val leadingStandaloneCircleRegex = Regex("^[O0o]\\s+")
    private val duplicatedLeadingCircleRegex = Regex("^[O0o](?=[oO][a-z])")

    fun cleanText(text: String): String {
        return text.replace("\n", " ")
            .replace(nonAlphanumericRegex, "")
            .trim()
    }

    fun cleanTextStrippingLeadingO(text: String): String {
        val cleanedText = text.trim()
            .replace(leadingMarkerRegex, "")
            .replace(leadingStandaloneCircleRegex, "")
            .replace(duplicatedLeadingCircleRegex, "")

        return cleanedText.ifEmpty { text }
    }

    fun cleanTextPreservingLeadingO(text: String): String {
        var cleanedText = text.trim()
            .replace(Regex("^[\\[\\]()●○□☑✓-]+\\s*"), "")

        cleanedText = cleanedText.replace(Regex("^[DT]?opti[oa]n", RegexOption.IGNORE_CASE), "Option")
        cleanedText = cleanedText.replace(Regex("^pti[oa]n", RegexOption.IGNORE_CASE), "Option")
        cleanedText = cleanedText.replace(Regex("^optton", RegexOption.IGNORE_CASE), "Option")

        return cleanedText.ifEmpty { text }
    }
}
