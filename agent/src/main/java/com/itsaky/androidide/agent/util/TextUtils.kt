package com.itsaky.androidide.agent.util

/**
 * Splits the string on newline characters while preserving empty trailing lines.
 * Carriage returns are removed from the end of each line.
 */
fun String.splitLinesPreserveEnding(): List<String> {
    if (isEmpty()) return emptyList()
    val parts = splitToSequence('\n').toList()
    if (parts.isEmpty()) return emptyList()
    val trimmed = if (parts.last().isEmpty()) parts.dropLast(1) else parts
    return trimmed.map { line -> line.removeSuffix("\r") }
}
