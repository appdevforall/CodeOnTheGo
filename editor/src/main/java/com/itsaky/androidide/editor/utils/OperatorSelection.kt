/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.editor.utils

import io.github.rosemoe.sora.text.Content

/**
 * Java/Kotlin operators for long-press selection, ordered by length descending
 * so longer matches are tried first (e.g. `>>>=` before `>>>` before `>>` before `>`).
 */
private val OPERATORS: List<String> =
    listOf(
        // 4-char (`>>>=` must precede `>>>` — the latter is a prefix)
        ">>>=",
        // 3-char (=== and !== before 2-char == and !=)
        "===",
        "!==",
        ">>>",
        "<<=",
        ">>=",
        // 2-char
        "==",
        "!=",
        "<=",
        ">=",
        "+=",
        "-=",
        "*=",
        "/=",
        "%=",
        "&=",
        "|=",
        "^=",
        "++",
        "--",
        "&&",
        "||",
        "<<",
        ">>",
        "->",
        "?.",
        "?:",
        "..",
        "!!",
        "::",
        // 1-char
        "+",
        "-",
        "*",
        "/",
        "%",
        "=",
        "<",
        ">",
        "!",
        "&",
        "|",
        "^",
        "~",
        "?",
        ":",
        ";",
        ",",
        ".",
        "@",
        "(",
        ")",
        "[",
        "]",
        "{",
        "}",
    )

private val OPERATOR_SET: Set<String> = OPERATORS.toSet()

/**
 * Tokens matched for editor selection that exist only in Kotlin, not Java.
 * Used so [isJavaOperatorToken] does not emit `java.operator.*` tags for these.
 */
private val KOTLIN_ONLY_OPERATOR_TOKENS: Set<String> =
    setOf(
        "?.",
        "?:",
        "..",
        "!!",
        "===",
        "!==",
    )

private val JAVA_OPERATOR_SET: Set<String> = OPERATOR_SET - KOTLIN_ONLY_OPERATOR_TOKENS

/**
 * Returns true when [text] is exactly one Kotlin operator/punctuation token for tooltip lookup
 * (same token set as the long-press operator list).
 */
fun isKotlinOperatorToken(text: CharSequence): Boolean {
    if (text.isEmpty()) return false
    return OPERATOR_SET.contains(text.toString())
}

/**
 * Returns true when [text] is exactly one Java operator/punctuation token for tooltip lookup.
 * Excludes Kotlin-only tokens such as `?.`, `..`, `===`, and `!==`.
 */
fun isJavaOperatorToken(text: CharSequence): Boolean {
    if (text.isEmpty()) return false
    return JAVA_OPERATOR_SET.contains(text.toString())
}

/**
 * Returns the column range of the operator at the given position, if any.
 * Columns are 0-based; endColumn is exclusive (one past the last character).
 *
 * @param lineContent The full line text.
 * @param column Cursor column (0-based).
 * @return (startColumn, endColumnExclusive) or null if no operator at this position.
 */
fun getOperatorRangeAt(lineContent: CharSequence, column: Int): Pair<Int, Int>? {
    if (column < 0 || column >= lineContent.length) return null
    val suffix = lineContent.subSequence(column, lineContent.length)
    for (op in OPERATORS) {
        if (op.length <= suffix.length && suffix.subSequence(0, op.length) == op) {
            return column to (column + op.length)
        }
    }
    return null
}

/**
 * Returns the column range of the operator at (line, column) in [content], if any.
 * Columns are 0-based; endColumn is exclusive.
 */
fun Content.getOperatorRangeAt(line: Int, column: Int): Pair<Int, Int>? {
    if (line < 0 || line >= lineCount) return null
    val lineContent = getLine(line)
    val maxColumn = lineContent.length
    if (column < 0 || column > maxColumn) return null
    val range = getOperatorRangeAt(lineContent, column) ?: return null
    val (start, end) = range
    if (end > maxColumn) return null
    return range
}
