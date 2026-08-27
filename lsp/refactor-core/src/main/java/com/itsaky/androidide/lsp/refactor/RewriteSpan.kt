package com.itsaky.androidide.lsp.refactor

import com.itsaky.androidide.lsp.models.TextEdit
import com.itsaky.androidide.models.Position
import com.itsaky.androidide.models.Range

/**
 * Deliberately a single replacement, not a list of edits: `IDELanguageClientImpl.applyActionEdits` runs
 * each `TextEdit` in its own `runOnUiThread` with no `beginBatchEdit`, against the *original* offsets --
 * so N edits would land on positions already shifted by their predecessors, and cost N undo steps.
 */
data class RewriteSpan(
	val span: TextSpan,
	val newText: String,
)

/** All three of [Position]'s fields are filled, so no consumer of either path sees a stale value. */
fun RewriteSpan.toTextEdit(fileText: String): TextEdit =
	TextEdit(
		range =
			Range(
				positionAt(fileText, span.start),
				positionAt(fileText, span.end),
			),
		newText = newText,
	)

fun positionAt(
	text: String,
	offset: Int,
): Position {
	val clamped = offset.coerceIn(0, text.length)
	var line = 0
	var lineStart = 0
	var i = 0
	while (i < clamped) {
		if (text[i] == '\n') {
			line++
			lineStart = i + 1
		}
		i++
	}
	return Position(line, clamped - lineStart, clamped)
}
