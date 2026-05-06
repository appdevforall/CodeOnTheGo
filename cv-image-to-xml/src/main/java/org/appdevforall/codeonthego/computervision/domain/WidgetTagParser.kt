package org.appdevforall.codeonthego.computervision.domain

/**
 * Parses and normalizes raw OCR text into standardized Android widget tags.
 * Handles common OCR misreads and formatting inconsistencies.
 */
internal object WidgetTagParser {
    private val tagRegex = Regex("^(?i)(B|P|D|T|C|R|SW|S)-[A-Z0-9_]+$")
    private val tagExtractRegex = Regex("^(?i)(SW|S\\s*8|8\\s*W|[BPDTCRS8]\\s*W?)[^a-zA-Z0-9]*([A-Z0-9_\\-]+)(?:\\s+(.+))?$")

    /**
     * Checks if the given text represents a valid, normalized widget tag.
     */
    fun isTag(text: String): Boolean = normalizeTagText(text).matches(tagRegex)

    /**
     * Normalizes raw OCR text into a standard tag format (e.g., "Prefix-Token").
     * Fixes common OCR prefix misreads, such as reading 'B' as '8'.
     */
    fun normalizeTagText(text: String): String {
        val trimmed = text.trim().trimEnd('.', ',', ';', ':', '_', '|')
        val match = tagExtractRegex.find(trimmed) ?: return trimmed.uppercase()

        var prefix = match.groupValues[1].replace(Regex("\\s+"), "").uppercase()
        if (prefix == "8") prefix = "B"
        if (prefix == "8W" || prefix == "S8") prefix = "SW"

        val token = normalizeTagToken(match.groupValues[2].trim('-'))
        return "$prefix-$token"
    }

    /**
     * Extracts a normalized widget tag and any remaining trailing text from a raw string.
     * * @return A Pair containing the [normalized tag, trailing text], or null if no valid tag is found.
     */
    fun extractTag(text: String): Pair<String, String?>? {
        val trimmed = text.trim().trimEnd('.', ',', ';', ':', '_', '|')
        val match = tagExtractRegex.find(trimmed) ?: return null

        var prefix = match.groupValues[1].replace(Regex("\\s+"), "").uppercase()
        if (prefix == "8") prefix = "B"
        if (prefix == "8W" || prefix == "S8") prefix = "SW"

        val token = normalizeTagToken(match.groupValues[2].trim('-'))
        val tag = "$prefix-$token"
        val trailingText = match.groupValues[3].takeIf { it.isNotBlank() }

        return (tag to trailingText).takeIf { isTag(tag) }
    }

    /**
     * Extracts the numeric or alphanumeric identifier part of the tag (the part after the hyphen).
     */
    fun extractOrdinal(tag: String): Int? = tag.substringAfter('-', "").toIntOrNull()

    /**
     * Cleans up the token suffix. If the token consists entirely of numbers or OCR artifacts,
     * it converts those artifacts back to digits. Otherwise, it replaces invalid characters with underscores.
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
