package org.appdevforall.codeonthego.computervision.domain

/**
 * Parses and normalizes raw OCR text into standardized Android widget tags.
 * Handles common OCR misreads and formatting inconsistencies.
 */
internal object WidgetTagParser {
    private val tagRegex = Regex("^(?i)(B|P|D|T|C|R|SW|S)-[A-Z0-9_]+$")
    private val tagExtractRegex = Regex("^(?i)(B|P|D|T|C|R|SW|S|8|8W|S8)([\\s\\-_.,|/]*)([A-Z0-9_\\-]+)")
    private val VALID_PREFIXES = setOf("B", "P", "D", "T", "C", "R", "SW", "S")

    fun isTag(text: String): Boolean {
        val cleaned = text.trim().trimEnd('.', ',', ';', ':', '_', '|')
        val match = tagExtractRegex.find(cleaned) ?: return false

        if (!isValidTagMatch(match)) return false

        val trailingText = cleaned.substring(match.range.last + 1).trim()
        if (trailingText.isNotBlank() && trailingText.any { it.isLetterOrDigit() }) return false

        return normalizeTagText(cleaned).matches(tagRegex)
    }

    private fun parseTagParts(match: MatchResult): Pair<String, String>? {
        val rawPrefix = match.groupValues[1]
        val prefix = normalizePrefix(rawPrefix)

        if (prefix !in VALID_PREFIXES) return null

        var tokenRaw = match.groupValues[3].trim('-')

        val upperToken = tokenRaw.uppercase()
        val remainder = upperToken.removePrefix(prefix)

        when {
            upperToken.startsWith("$prefix-") || upperToken.startsWith("${prefix}_") -> {
                tokenRaw = tokenRaw.substring(prefix.length + 1).trim('-')
            }
            upperToken.startsWith(prefix) && remainder.isNotEmpty() && remainder.all(::isNumericLikeOcrChar) -> {
                tokenRaw = remainder
            }
        }

        val token = normalizeTagToken(tokenRaw)
        return prefix to token
    }

    fun normalizeTagText(text: String): String {
        val cleaned = text.trim().trimEnd('.', ',', ';', ':', '_', '|')
        val match = tagExtractRegex.find(cleaned) ?: return cleaned.uppercase()

        if (!isValidTagMatch(match)) return cleaned.uppercase()

        val parts = parseTagParts(match) ?: return cleaned.uppercase()

        return "${parts.first}-${parts.second}"
    }

    fun extractTag(text: String): Pair<String, String?>? {
        val cleaned = text.trim().trimEnd('.', ',', ';', ':', '_', '|')
        val match = tagExtractRegex.find(cleaned) ?: return null

        if (!isValidTagMatch(match)) return null

        val parts = parseTagParts(match) ?: return null

        val finalTag = "${parts.first}-${parts.second}"

        if (!finalTag.matches(tagRegex)) return null

        val trailingText = cleaned.substring(match.range.last + 1).trim().takeIf { it.isNotBlank() }
        return finalTag to trailingText
    }

    private fun isValidTagMatch(match: MatchResult): Boolean {
        val separator = match.groupValues[2]
        val rawToken = match.groupValues[3]

        if (separator.isNotEmpty()) return true
        return rawToken.all(::isNumericLikeOcrChar)
    }

    private fun normalizePrefix(rawPrefix: String): String {
        return rawPrefix.uppercase()
            .replace(Regex("\\s+"), "")
            .replace(Regex("^8$"), "B")
            .replace(Regex("^(8W|S8)$"), "SW")
    }

    /**
     * Extracts the numeric or alphanumeric identifier part of the tag (the part after the hyphen).
     */
    fun extractOrdinal(tag: String): Int? = tag.substringAfter('-', "").toIntOrNull()

    /**
     * Cleans up the token suffix. If the token consists entirely of numbers or OCR artifacts,
     * it converts those artifacts back to digits.
     */
    private fun normalizeTagToken(rawToken: String): String {
        if (rawToken.isBlank()) return rawToken

        val uppercaseToken = rawToken.uppercase().replace('-', '_')
        return if (uppercaseToken.all(::isNumericLikeOcrChar)) {
            normalizeOcrDigits(uppercaseToken)
        } else {
            uppercaseToken.replace(Regex("[^A-Z0-9_]"), "_")
        }
    }

    /**
     * Replaces characters that are commonly misread by OCR with their intended numeric values.
     */
    private fun normalizeOcrDigits(raw: String): String =
        raw.replace('I', '1')
            .replace('L', '1')
            .replace('!', '1')
            .replace('O', '0')
            .replace('Z', '2')
            .replace('S', '5')
            .replace('B', '6')

    /**
     * Determines whether a character is a digit or a letter frequently confused with a digit by OCR.
     */
    private fun isNumericLikeOcrChar(char: Char): Boolean {
        return char.isDigit() || char.uppercaseChar() in setOf('O', 'I', 'L', 'Z', 'S', 'B', '!')
    }
}
