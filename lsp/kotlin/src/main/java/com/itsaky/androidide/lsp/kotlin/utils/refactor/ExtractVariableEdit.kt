package com.itsaky.androidide.lsp.kotlin.utils.refactor

import com.itsaky.androidide.lsp.models.TextEdit
import com.itsaky.androidide.models.Position
import com.itsaky.androidide.models.Range

/**
 * The one text replacement an extraction performs: replace `[span]` with [newText].
 *
 * **Deliberately a single replacement, not a list of edits.** `IDELanguageClientImpl.applyActionEdits`
 * applies each `TextEdit` in its own `runOnUiThread` with no `beginBatchEdit`, and every range is
 * computed against the *original* text -- so a list of N edits would be applied against positions
 * already shifted by its predecessors, and would cost the user N undo steps with a typing window
 * between each. Rewriting one contiguous span sidesteps all of it.
 */
data class RewriteSpan(
	val span: TextSpan,
	val newText: String,
)

/**
 * Builds the extraction rewrite, or null when the inputs cannot produce one.
 *
 * [name] is the final variable name -- the caller has already validated it. [replaceAll] selects
 * between every occurrence in [scope] and only [candidateSpan].
 *
 * Occurrences are substituted right-to-left within the rewritten span so earlier substitutions
 * cannot shift later offsets, and the whole span is emitted as one replacement.
 */
fun buildExtractVariableRewrite(
	fileText: String,
	candidateSpan: TextSpan,
	scope: ScopeOption,
	name: String,
	replaceAll: Boolean,
): RewriteSpan? {
	val targets =
		(if (replaceAll) scope.occurrences else listOf(candidateSpan))
			.sortedBy { it.start }
			.takeIf { it.isNotEmpty() } ?: return null
	if (targets.any { it.end > fileText.length }) return null

	val expression = fileText.substring(candidateSpan.start, candidateSpan.end)
	val declaration = "val $name = $expression"

	return when (val form = scope.anchorForm) {
		AnchorForm.ExistingBlock -> existingBlockRewrite(fileText, targets, declaration, name)
		is AnchorForm.WrapInBraces -> wrapInBracesRewrite(fileText, form, targets, declaration, name)
		is AnchorForm.ConvertExpressionBody -> convertExpressionBodyRewrite(fileText, form, targets, declaration, name)
	}
}

/**
 * Inserts the declaration as its own line before the first served occurrence's line, and rewrites
 * everything from there through the last occurrence.
 *
 * The rewritten span starts at that line's start (not at the occurrence) so the declaration lands on
 * a line of its own at the right indentation, and ends at the last occurrence so untouched trailing
 * code is left alone.
 */
private fun existingBlockRewrite(
	fileText: String,
	targets: List<TextSpan>,
	declaration: String,
	name: String,
): RewriteSpan {
	val first = targets.first()
	val last = targets.last()
	val lineStart = lineStartOffset(fileText, first.start)
	val indent = leadingIndentAt(fileText, first.start)
	val newline = detectNewline(fileText)

	val body = replaceOccurrences(fileText, TextSpan(lineStart, last.end), targets, name)
	return RewriteSpan(
		span = TextSpan(lineStart, last.end),
		newText = indent + declaration + newline + body,
	)
}

/** Wraps a braceless statement in a block containing the declaration and the original statement. */
private fun wrapInBracesRewrite(
	fileText: String,
	form: AnchorForm.WrapInBraces,
	targets: List<TextSpan>,
	declaration: String,
	name: String,
): RewriteSpan {
	// Occurrences in a braceless scope are confined to the statement itself (the frame's search
	// range *is* this span), so no cross-span targets are possible; replaceOccurrences filters anyway.
	val span = TextSpan(form.bodyStart, form.bodyEnd)
	val newline = detectNewline(fileText)
	val body = replaceOccurrences(fileText, span, targets, name)

	val newText =
		buildString {
			append('{').append(newline)
			append(form.innerIndent).append(declaration).append(newline)
			append(form.innerIndent).append(body).append(newline)
			append(form.indent).append('}')
		}
	return RewriteSpan(span, newText)
}

/** Converts `= expr` into a block body holding the declaration and a `return` of the rewritten body. */
private fun convertExpressionBodyRewrite(
	fileText: String,
	form: AnchorForm.ConvertExpressionBody,
	targets: List<TextSpan>,
	declaration: String,
	name: String,
): RewriteSpan {
	val bodySpan = TextSpan(form.bodyStart, form.bodyEnd)
	val newline = detectNewline(fileText)
	val body = replaceOccurrences(fileText, bodySpan, targets, name)
	val returned = if (form.needsReturn) "return $body" else body

	// Writing a type means rewriting from the end of the signature, not from the `=`: starting at the
	// `=` would leave the space in front of it and emit `fun area(r: Int) : Int {`.
	val spanStart =
		if (form.returnTypeText == null) form.assignStart else startOfWhitespaceBefore(fileText, form.assignStart)
	val header = form.returnTypeText?.let { ": $it " } ?: ""

	val newText =
		buildString {
			append(header).append('{').append(newline)
			append(form.innerIndent).append(declaration).append(newline)
			append(form.innerIndent).append(returned).append(newline)
			append(form.indent).append('}')
		}
	return RewriteSpan(TextSpan(spanStart, form.bodyEnd), newText)
}

/** The offset where the run of whitespace ending at [offset] begins. */
private fun startOfWhitespaceBefore(
	text: String,
	offset: Int,
): Int {
	var index = offset.coerceIn(0, text.length)
	while (index > 0 && text[index - 1].isWhitespace()) index--
	return index
}

/**
 * Returns `[span]`'s text with every occurrence inside it replaced by [name]. Substitutes
 * right-to-left so an earlier replacement cannot invalidate a later offset.
 */
private fun replaceOccurrences(
	fileText: String,
	span: TextSpan,
	targets: List<TextSpan>,
	name: String,
): String {
	val builder = StringBuilder(fileText.substring(span.start, span.end))
	targets
		.filter { it.start >= span.start && it.end <= span.end }
		.sortedByDescending { it.start }
		.forEach { builder.replace(it.start - span.start, it.end - span.start, name) }
	return builder.toString()
}

/** CRLF only when the file already uses it, so the edit does not mix line endings. */
internal fun detectNewline(text: String): String = if (text.contains("\r\n")) "\r\n" else "\n"

/**
 * Converts a [RewriteSpan] into the `TextEdit` the language client consumes. [Position] carries
 * line, column *and* index; all three are filled so neither the client's line/column path nor any
 * index-based consumer sees a stale value.
 */
fun RewriteSpan.toTextEdit(fileText: String): TextEdit =
	TextEdit(
		range =
			Range(
				positionAt(fileText, span.start),
				positionAt(fileText, span.end),
			),
		newText = newText,
	)

internal fun positionAt(
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
