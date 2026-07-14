package com.itsaky.androidide.lsp.kotlin.utils

import com.itsaky.androidide.lsp.models.TextEdit
import com.itsaky.androidide.models.Position
import com.itsaky.androidide.models.Range

/**
 * Wraps lines [startLine]..[endLine] (0-based, inclusive) of [text] in a
 * try/catch block. Whole-line based: columns are ignored and full lines are
 * replaced. Indentation is computed here so the result is correct even without a
 * follow-up formatter. Returns null when the span is blank or out of range.
 */
fun computeSurroundWithTryCatchEdit(
	text: String,
	startLine: Int,
	endLine: Int,
): TextEdit? {
	val lines = text.split("\n")
	if (startLine < 0 || startLine > endLine || endLine >= lines.size) {
		return null
	}

	val selected = lines.subList(startLine, endLine + 1)
	if (selected.all(String::isBlank)) {
		return null
	}

	val baseIndent =
		selected.first(String::isNotBlank).takeWhile { it == ' ' || it == '\t' }
	val body = selected.joinToString("\n") { if (it.isBlank()) it else "\t$it" }

	val newText = buildString {
		append(baseIndent).append("try {\n")
		append(body).append('\n')
		append(baseIndent).append("} catch (e: Exception) {\n")
		append(baseIndent).append("\te.printStackTrace()\n")
		append(baseIndent).append("}")
	}

	val startIndex = lineStartIndex(lines, startLine)
	val endCol = lines[endLine].length
	val endIndex = lineStartIndex(lines, endLine) + endCol

	return TextEdit(
		range = Range(
			Position(startLine, 0, startIndex),
			Position(endLine, endCol, endIndex),
		),
		newText = newText,
	)
}

private fun lineStartIndex(lines: List<String>, line: Int): Int {
	var index = 0
	for (i in 0 until line) {
		index += lines[i].length + 1
	}
	return index
}
