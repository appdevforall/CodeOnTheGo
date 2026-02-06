package org.appdevforall.codeonthego.lsp.kotlin.parser

object PositionConverter {

    fun byteOffsetToCharIndex(source: String, byteOffset: Int): Int {
        if (byteOffset <= 0) return 0
        if (source.isEmpty()) return 0

        var byteCount = 0
        var charIndex = 0

        while (charIndex < source.length && byteCount < byteOffset) {
            val char = source[charIndex]

            byteCount += when {
                Character.isHighSurrogate(char) -> 4
                Character.isLowSurrogate(char) -> 0
                else -> 2
            }
            charIndex++
        }

        return minOf(charIndex, source.length)
    }

    fun charIndexToByteOffset(source: String, charIndex: Int): Int {
        if (charIndex <= 0) return 0
        if (source.isEmpty()) return 0

        var byteCount = 0
        val endIndex = minOf(charIndex, source.length)

        for (i in 0 until endIndex) {
            val char = source[i]

            byteCount += when {
                Character.isHighSurrogate(char) -> 4
                Character.isLowSurrogate(char) -> 0
                else -> 2
            }
        }

        return byteCount
    }

    fun columnToCharColumn(lineText: String, column: Int): Int {
        return byteOffsetToCharIndex(lineText, column)
    }

    fun charColumnToColumn(lineText: String, charColumn: Int): Int {
        return charIndexToByteOffset(lineText, charColumn)
    }

    fun lineAndColumnToCharOffset(source: String, line: Int, column: Int): Int {
        if (source.isEmpty()) return 0

        var currentLine = 0
        var lineStart = 0

        for (i in source.indices) {
            if (currentLine == line) {
                break
            }
            if (source[i] == '\n') {
                currentLine++
                lineStart = i + 1
            }
        }

        if (currentLine != line) {
            return source.length
        }

        val lineEnd = source.indexOf('\n', lineStart).let { if (it < 0) source.length else it }
        val lineText = source.substring(lineStart, lineEnd)
        val charColumn = columnToCharColumn(lineText, column)

        return minOf(lineStart + charColumn, source.length)
    }

    fun charOffsetToLineAndColumn(source: String, charOffset: Int): Pair<Int, Int> {
        if (source.isEmpty()) return 0 to 0

        var line = 0
        var lineStart = 0
        val safeOffset = minOf(charOffset, source.length)

        for (i in 0 until safeOffset) {
            if (source[i] == '\n') {
                line++
                lineStart = i + 1
            }
        }

        val column = safeOffset - lineStart
        return line to column
    }

    fun extractSubstring(source: String, startByte: Int, endByte: Int): String {
        val startChar = byteOffsetToCharIndex(source, startByte)
        val endChar = byteOffsetToCharIndex(source, endByte)

        return if (startChar >= 0 && endChar <= source.length && startChar <= endChar) {
            source.substring(startChar, endChar)
        } else {
            ""
        }
    }
}
