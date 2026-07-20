package com.itsaky.androidide.lsp.kotlin.utils

import com.itsaky.androidide.lsp.models.TextEdit
import com.itsaky.androidide.models.Position
import com.itsaky.androidide.models.Range

/**
 * Resolves an editor selection (cursor left/right line+column) to the whole-line
 * span the surround action wraps. Whole-line based by design: mid-line columns
 * still select the entire line -- statement-boundary snapping needs PSI, which is
 * out of scope, so a wrapped `val x = ...` on a multi-statement line stays scoped
 * inside the try. A selection whose end handle sits at column 0 of the line after
 * the last selected line (the common "drag to select whole lines" gesture) would
 * otherwise wrap that trailing, visually-unselected line, so it is trimmed.
 * Returns (startLine, endLine), 0-based inclusive.
 */
fun resolveSurroundLines(
	leftLine: Int,
	leftColumn: Int,
	rightLine: Int,
	rightColumn: Int,
): Pair<Int, Int> {
	val endLine =
		if (rightColumn == 0 && rightLine > leftLine) rightLine - 1 else rightLine
	return leftLine to endLine
}

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
	val nl = if (text.contains("\r\n")) "\r\n" else "\n"
	val lines = text.split(nl)
	if (startLine < 0 || startLine > endLine || endLine >= lines.size) {
		return null
	}

	val selected = lines.subList(startLine, endLine + 1)
	if (selected.all(String::isBlank)) {
		return null
	}

	val baseIndent =
		selected.first(String::isNotBlank).takeWhile { it == ' ' || it == '\t' }
	val indentUnit = detectIndentUnit(lines)
	val body =
		selected.joinToString(nl) { if (it.isBlank()) it else "$indentUnit$it" }

	val newText = buildString {
		append(baseIndent).append("try {").append(nl)
		append(body).append(nl)
		append(baseIndent).append("} catch (e: Exception) {").append(nl)
		append(baseIndent).append(indentUnit).append("e.printStackTrace()").append(nl)
		append(baseIndent).append("}")
	}

	val startIndex = lineStartIndex(lines, startLine, nl)
	val endCol = lines[endLine].length
	val endIndex = lineStartIndex(lines, endLine, nl) + endCol

	return TextEdit(
		range = Range(
			Position(startLine, 0, startIndex),
			Position(endLine, endCol, endIndex),
		),
		newText = newText,
	)
}

/** 4 spaces if the file's first indented line starts with a space, else a tab. */
private fun detectIndentUnit(lines: List<String>): String {
	val firstIndented =
		lines.firstOrNull { it.isNotEmpty() && (it[0] == ' ' || it[0] == '\t') }
	return if (firstIndented != null && firstIndented[0] == ' ') "    " else "\t"
}

private fun lineStartIndex(lines: List<String>, line: Int, nl: String): Int {
	var index = 0
	for (i in 0 until line) {
		index += lines[i].length + nl.length
	}
	return index
}
