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
	// Only targets are bounds-checked against fileText; contentSpan/statementSpans are trusted
	// unchecked. That is safe only because fileText is the plan's own text, not the live document --
	// if a caller ever passed live text here instead, those spans would need the same check.
	if (targets.any { it.end > fileText.length }) return null

	val expression = fileText.substring(candidateSpan.start, candidateSpan.end)
	val declaration = "val $name = $expression"

	return when (val form = scope.anchorForm) {
		is AnchorForm.ExistingBlock -> existingBlockRewrite(fileText, form, targets, declaration, name)
		is AnchorForm.WrapInBraces -> wrapInBracesRewrite(fileText, form, targets, declaration, name)
		is AnchorForm.ConvertExpressionBody -> convertExpressionBodyRewrite(fileText, form, targets, declaration, name)
	}
}

/**
 * What a block rung can do with the anchor statement holding a given target.
 *
 * Shared by the planner and the rewriter so a rung is never *offered* that the rewrite would then
 * refuse: the sheet would open, the user would fill it in, and the confirm would fail with the generic
 * quick-fix error instead of the action reporting up front that there is nothing to extract.
 */
internal sealed interface BlockPlacement {
	/** The declaration becomes a new line above [anchor], at [anchor]'s indentation. */
	data class LineAbove(
		val anchor: TextSpan,
	) : BlockPlacement

	/** The block is written on one line and is expanded, with the declaration inside its braces. */
	data object ExpandOneLine : BlockPlacement

	/** Neither is sound here, so the rung is declined. */
	data object Refused : BlockPlacement
}

/**
 * Decides the placement for the anchor statement of [form] that contains [firstTarget].
 *
 * [Refused] covers two shapes. Nothing in the block contains the target, which means the plan and the
 * text disagree. Or something other than indentation precedes the anchor statement on its line while
 * the block's own content spans several lines, as in `items.forEach { log(x)\n\tlog(y) }` -- anchoring
 * at that line start would put the declaration before the block's own opening delimiter, outside the
 * scope the user picked, where a lambda's `it` does not exist.
 *
 * A lambda body's content starts right at its first token with no owned whitespace, so `lineStart`
 * sits before `contentSpan.start` on plain indentation alone; that gap must not read as "outside the
 * block", which is why the second check tests the gap for real code rather than for mere distance.
 */
internal fun blockPlacementFor(
	fileText: String,
	form: AnchorForm.ExistingBlock,
	firstTarget: TextSpan,
): BlockPlacement {
	val anchor =
		form.statementSpans.firstOrNull { it.start <= firstTarget.start && firstTarget.end <= it.end }
			?: return BlockPlacement.Refused
	val lineStart = lineStartOffset(fileText, anchor.start)

	/*
	 * Two conditions together are what actually mean "written on one line": something other than
	 * indentation already precedes the statement on its line (the brace, a header, or a prior
	 * semicolon-separated statement), and the block's content itself contains no newline, so
	 * re-emitting it as a single line loses nothing.
	 */
	val linePrefix = fileText.substring(lineStart, anchor.start)
	val contentIsOneLine = !fileText.substring(form.contentSpan.start, form.contentSpan.end).contains('\n')
	if (linePrefix.isNotBlank() && contentIsOneLine) return BlockPlacement.ExpandOneLine

	if (form.contentSpan.start > lineStart && fileText.substring(lineStart, form.contentSpan.start).isNotBlank()) {
		return BlockPlacement.Refused
	}
	return BlockPlacement.LineAbove(anchor)
}

/**
 * Inserts the declaration as its own line before the anchor statement, and rewrites everything from
 * there through the last occurrence.
 *
 * The anchor is the statement *of this scope* that holds the first served occurrence, so picking an
 * outer rung hoists the declaration above the enclosing statement rather than leaving it where the
 * inner rung would have put it. The rewritten span starts at that statement's line start so the
 * declaration lands on a line of its own at the right indentation, and ends at the last occurrence so
 * untouched trailing code is left alone.
 *
 * Null when [blockPlacementFor] refuses the anchor; the caller reports that rather than guessing.
 */
private fun existingBlockRewrite(
	fileText: String,
	form: AnchorForm.ExistingBlock,
	targets: List<TextSpan>,
	declaration: String,
	name: String,
): RewriteSpan? {
	val last = targets.last()
	val anchor =
		when (val placement = blockPlacementFor(fileText, form, targets.first())) {
			is BlockPlacement.Refused -> return null
			is BlockPlacement.ExpandOneLine -> return oneLineBlockRewrite(fileText, form, targets, declaration, name)
			is BlockPlacement.LineAbove -> placement.anchor
		}

	val lineStart = lineStartOffset(fileText, anchor.start)
	val indent = leadingIndentAt(fileText, anchor.start)
	val newline = detectNewline(fileText)

	val span = TextSpan(lineStart, last.end)
	val body = replaceOccurrences(fileText, span, targets, name)
	return RewriteSpan(span = span, newText = indent + declaration + newline + body)
}

/**
 * Puts the declaration inside a block written on one line, moving the block's content and its closing
 * brace onto their own lines.
 *
 * Only the content between the braces is rewritten: the braces, and a lambda's `param ->` header,
 * stay exactly where they are, so the expansion cannot disturb the call around it.
 */
private fun oneLineBlockRewrite(
	fileText: String,
	form: AnchorForm.ExistingBlock,
	targets: List<TextSpan>,
	declaration: String,
	name: String,
): RewriteSpan {
	val content = form.contentSpan
	val newline = detectNewline(fileText)
	val indent = leadingIndentAt(fileText, content.start)
	val innerIndent = indent + detectIndentUnit(fileText)

	// A block that does not own its braces (a lambda body) stops short of them, leaving a single
	// space between the content span and the brace on each side. Widen the replaced span over that
	// gap so it does not survive the rewrite as a stray "{ " or " }".
	val span = TextSpan(startOfWhitespaceBefore(fileText, content.start), endOfWhitespaceAfter(fileText, content.end))
	val body = replaceOccurrences(fileText, content, targets, name).trim()

	val newText =
		buildString {
			append(newline)
			append(innerIndent).append(declaration).append(newline)
			append(innerIndent).append(body).append(newline)
			append(indent)
		}
	return RewriteSpan(span = span, newText = newText)
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

/** The offset where the run of whitespace starting at [offset] ends. */
private fun endOfWhitespaceAfter(
	text: String,
	offset: Int,
): Int {
	var index = offset.coerceIn(0, text.length)
	while (index < text.length && text[index].isWhitespace()) index++
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
