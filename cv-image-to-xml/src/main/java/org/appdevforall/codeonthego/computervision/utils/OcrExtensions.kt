package org.appdevforall.codeonthego.computervision.utils

private val PUNCTUATION_DELIMITERS = Regex("\\s*[,;|/\\n]+\\s*")
private val WHITESPACE_DELIMITERS = Regex("\\s+")
private val OCR_NUMERIC_PATTERN = Regex("^[0-9oOlIzZsSbB]+$")

/**
 * Extracts separated entries from a raw OCR string.
 * Tries to split by punctuation first. If no punctuation is found,
 * it falls back to space-separated tokens, provided they all look like numbers or OCR artifacts.
 */
internal fun String.extractOcrEntries(): List<String> {
    // Try to split by explicit punctuation or newlines
    val punctuatedTokens = this.split(PUNCTUATION_DELIMITERS).filter { it.isNotBlank() }

    // If successfully split into multiple items (or none), return them
    if (punctuatedTokens.size != 1) {
        return punctuatedTokens
    }

    // Fallback: check if elements were separated only by spaces
    val whitespaceTokens = this.trim().split(WHITESPACE_DELIMITERS).filter { it.isNotBlank() }

    // Only accept space separation if all resulting tokens look like numbers (or OCR artifacts)
    val isSpaceSeparatedOcrNumbers = whitespaceTokens.size > 1 &&
            whitespaceTokens.all { it.matches(OCR_NUMERIC_PATTERN) }

    return if (isSpaceSeparatedOcrNumbers) whitespaceTokens else punctuatedTokens
}