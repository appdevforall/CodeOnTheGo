package org.appdevforall.codeonthego.computervision.domain

/**
 * Parses and normalizes raw OCR text into standardized Android widget tags.
 * Handles common OCR misreads and formatting inconsistencies.
 */
internal object WidgetTagParser {
    private val tagRegex = Regex("^(?i)(B|P|D|T|C|R|SW|S)-[A-Z0-9_]+$")
    private val tagExtractRegex = Regex("^(?i)(SW|S\\s*8|8\\s*W|[BPDTCRS8]\\s*W?)([\\s\\-_]*)([A-Z0-9_\\-]+)")

    fun isTag(text: String): Boolean {
        val cleaned = text.trim().trimEnd('.', ',', ';', ':', '_', '|')
        val match = tagExtractRegex.find(cleaned) ?: return false

        if (!isValidTagMatch(match)) return false

        val trailingText = cleaned.substring(match.range.last + 1).trim()
        if (trailingText.isNotBlank() && trailingText.any { it.isLetterOrDigit() }) return false

        return normalizeTagText(cleaned).matches(tagRegex)
    }

    fun normalizeTagText(text: String): String {
        val cleaned = text.trim().trimEnd('.', ',', ';', ':', '_', '|')
        val match = tagExtractRegex.find(cleaned) ?: return cleaned.uppercase()

        if (!isValidTagMatch(match)) return cleaned.uppercase()

        val prefix = normalizePrefix(match.groupValues[1])
        var tokenRaw = match.groupValues[3].trim('-')

        if (tokenRaw.uppercase().startsWith(prefix)) {
            tokenRaw = tokenRaw.substring(prefix.length).trim('-')
        }

        val token = normalizeTagToken(tokenRaw)
        return "$prefix-$token"
    }

    fun extractTag(text: String): Pair<String, String?>? {
        val cleaned = text.trim().trimEnd('.', ',', ';', ':', '_', '|')
        val match = tagExtractRegex.find(cleaned) ?: return null

        if (!isValidTagMatch(match)) return null

        val prefix = normalizePrefix(match.groupValues[1])
        var tokenRaw = match.groupValues[3].trim('-')

        if (tokenRaw.uppercase().startsWith(prefix)) {
            tokenRaw = tokenRaw.substring(prefix.length).trim('-')
        }

        val token = normalizeTagToken(tokenRaw)
        val finalTag = "$prefix-$token"

        if (!finalTag.matches(tagRegex)) return null

        val trailingText = cleaned.substring(match.range.last + 1).trim().takeIf { it.isNotBlank() }
        return finalTag to trailingText
    }

    private fun isValidTagMatch(match: MatchResult): Boolean {
        val separator = match.groupValues[2]
        val rawToken = match.groupValues[3]
        return !(separator.isEmpty() && rawToken.firstOrNull()?.isLetter() == true)
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
        return char.isDigit() || char in setOf('O', 'I', 'L', 'Z', 'S', 'B', '!')
    }
}
