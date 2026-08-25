package com.itsaky.androidide.lsp.java.refactor

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

/**
 * Builds the extraction rewrite, or null when the inputs cannot produce one. [name] is already
 * validated. Occurrences are substituted right-to-left so an earlier one cannot shift a later offset.
 */
fun buildExtractVariableRewrite(
	fileText: String,
	candidateSpan: TextSpan,
	declaredType: String,
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
	val declaration = "$declaredType $name = $expression;"

	return when (val form = scope.anchorForm) {
		is AnchorForm.ExistingBlock -> existingBlockRewrite(fileText, form, targets, declaration, name)
		is AnchorForm.WrapInBraces -> wrapInBracesRewrite(fileText, form, targets, declaration, name)
		is AnchorForm.ConvertExpressionBody -> convertExpressionBodyRewrite(fileText, form, targets, declaration, name)
	}
}

/**
 * Shared by the planner and the rewriter so a rung is never *offered* that the rewrite would refuse --
 * otherwise the sheet opens, the user fills it in, and confirm fails with a generic error.
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
 * Refuses two shapes: nothing in the block contains the target (plan and text disagree), or something
 * besides indentation precedes the anchor statement while the block's content spans several lines, as
 * in `items.forEach(x -> { log(x);\n\tlog(y); })` -- anchoring at that line start would put the
 * declaration before the opening brace, outside the scope where a lambda parameter exists.
 *
 * [form]'s spans are substringed against [fileText] unchecked, so callers must pass the text those
 * spans were computed against -- the plan's own, never the live document.
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

	val linePrefix = fileText.substring(lineStart, anchor.start)
	// Nothing but indentation in front: the declaration can take its own line above, which is the common
	// case and the only one where the anchor's line needs no rearranging.
	if (linePrefix.isBlank()) return BlockPlacement.LineAbove(anchor)

	// Something shares the line. Expanding is sound only when the whole block is that one line, because
	// then re-emitting its content loses nothing; otherwise the declaration would have to be threaded
	// into a line that also holds unrelated statements, and hoisting it above them reorders execution.
	val contentIsOneLine = !fileText.substring(form.contentSpan.start, form.contentSpan.end).contains('\n')
	return if (contentIsOneLine) BlockPlacement.ExpandOneLine else BlockPlacement.Refused
}

/** The block statement holding [target], or null when the plan and the text disagree. */
internal fun anchorOf(
	form: AnchorForm.ExistingBlock,
	target: TextSpan,
): TextSpan? = form.statementSpans.firstOrNull { it.start <= target.start && target.end <= it.end }

/**
 * A replace-all anchors on the *first* served occurrence, so a leading one whose statement shares the
 * opening-brace line would refuse the whole rewrite even though the user's own site is placeable.
 * [candidateSpan] is never dropped; only leading sites matter, since a later one never becomes anchor.
 */
internal fun servableOccurrences(
	fileText: String,
	form: AnchorForm,
	occurrences: List<TextSpan>,
	candidateSpan: TextSpan,
): List<TextSpan> {
	if (form !is AnchorForm.ExistingBlock) return occurrences
	return occurrences.dropWhile { it != candidateSpan && blockPlacementFor(fileText, form, it) is BlockPlacement.Refused }
}

/**
 * Restricts [occurrences] to a contiguous run no write to a referenced mutable interrupts, since
 * `foo(limit + 1); limit = 5; foo(limit + 1);` is the same expression holding two different values.
 *
 * Unsound sites are excluded rather than warned about. The walk grows outward from the candidate, never
 * dropping the user's own site, and stops in each direction at the first write it would cross.
 *
 * The guarantee is bounded to **variable writes**, which is what [writeOffsetsFor] can see. Collapsing
 * repeated evaluations of an effectful expression is left alone deliberately -- it is what Extract
 * Variable means, and it is what every IDE does -- so `foo(items.size()); items.add(x);
 * bar(items.size());` does fold to one read, and `foo(it.next()); bar(it.next());` to one advance. What
 * this function rules out is the case where the *same* text provably names two different values.
 */
internal fun excludeUnsoundOccurrences(
	occurrences: List<TextSpan>,
	candidateSpan: TextSpan,
	writeOffsets: List<Int>,
): List<TextSpan> {
	if (occurrences.isEmpty()) return occurrences
	val ordered = occurrences.sortedBy { it.start }
	val candidateIndex = ordered.indexOfFirst { it.start == candidateSpan.start && it.end == candidateSpan.end }
	if (candidateIndex < 0) return listOf(candidateSpan)

	val writes = writeOffsets.sorted()

	fun writeBetween(
		from: Int,
		to: Int,
	): Boolean = writes.any { it in from until to }

	val accepted = mutableListOf(ordered[candidateIndex])
	for (i in candidateIndex - 1 downTo 0) {
		if (writeBetween(ordered[i].end, ordered[candidateIndex].start)) break
		accepted.add(0, ordered[i])
	}
	for (i in candidateIndex + 1 until ordered.size) {
		if (writeBetween(ordered[candidateIndex].end, ordered[i].start)) break
		accepted += ordered[i]
	}
	return accepted
}

/**
 * The anchor is the statement *of this scope* holding the first served occurrence, so picking an outer
 * rung hoists the declaration above the enclosing statement. The span ends at the last occurrence, so
 * untouched trailing code is left alone.
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
			is BlockPlacement.Refused -> {
				return null
			}

			is BlockPlacement.ExpandOneLine -> {
				return oneLineBlockRewrite(fileText, form, targets, declaration, name, anchorOf(form, targets.first()))
			}

			is BlockPlacement.LineAbove -> {
				placement.anchor
			}
		}

	val lineStart = lineStartOffset(fileText, anchor.start)
	val indent = leadingIndentAt(fileText, anchor.start)
	val newline = detectNewline(fileText)

	val span = TextSpan(lineStart, last.end)
	val body = replaceOccurrences(fileText, span, targets, name)
	return RewriteSpan(span = span, newText = indent + declaration + newline + body)
}

/**
 * Expands a block written on one line. Only the content between the braces is rewritten, so the braces
 * and anything before them (a `param ->` header, a `case A ->` label) stay put.
 */
private fun oneLineBlockRewrite(
	fileText: String,
	form: AnchorForm.ExistingBlock,
	targets: List<TextSpan>,
	declaration: String,
	name: String,
	anchor: TextSpan?,
): RewriteSpan {
	val content = form.contentSpan
	val newline = detectNewline(fileText)
	val indent = leadingIndentAt(fileText, content.start)
	val innerIndent = indent + detectIndentUnit(fileText)

	// Widen over the whitespace on each side of the content so it does not survive the rewrite as a
	// stray "{ " or " }".
	val span = TextSpan(startOfWhitespaceBefore(fileText, content.start), endOfWhitespaceAfter(fileText, content.end))

	// Anything already in front of the anchor stays in front of it: prepending the declaration to the
	// whole block would hoist it above statements the expression depends on.
	val anchorStart = anchor?.start?.coerceIn(content.start, content.end) ?: content.start
	val before = fileText.substring(content.start, anchorStart).trim()
	val body = replaceOccurrences(fileText, TextSpan(anchorStart, content.end), targets, name).trim()

	val newText =
		buildString {
			append(newline)
			if (before.isNotEmpty()) append(innerIndent).append(before).append(newline)
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
	// Occurrences in a braceless scope are confined to the statement itself (the frame's search range
	// *is* this span), so no cross-span targets are possible; replaceOccurrences filters anyway.
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

/** The returned expression gains a `;` because it becomes a statement; the expression body had none. */
private fun convertExpressionBodyRewrite(
	fileText: String,
	form: AnchorForm.ConvertExpressionBody,
	targets: List<TextSpan>,
	declaration: String,
	name: String,
): RewriteSpan {
	val bodySpan = TextSpan(form.bodyStart, form.bodyEnd)
	val newline = detectNewline(fileText)
	// A switch rule's span reaches past its own `;` (the parser consumes it separately), so strip it
	// before composing or the block ends up with `yield v;;`.
	val body = replaceOccurrences(fileText, bodySpan, targets, name).trimEnd().removeSuffix(";")
	val returned = if (form.needsReturn) "${form.returnKeyword} $body;" else "$body;"

	val newText =
		buildString {
			append('{').append(newline)
			append(form.innerIndent).append(declaration).append(newline)
			append(form.innerIndent).append(returned).append(newline)
			append(form.indent).append('}')
		}
	return RewriteSpan(bodySpan, newText)
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

/** Right-to-left, so an earlier replacement cannot invalidate a later offset. */
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

/** Offset of the start of the line containing [offset]. */
internal fun lineStartOffset(
	text: String,
	offset: Int,
): Int = text.lastIndexOf('\n', (offset - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }

/** The run of spaces/tabs at the start of [offset]'s line. */
internal fun leadingIndentAt(
	text: String,
	offset: Int,
): String {
	val lineStart = lineStartOffset(text, offset)
	return text.substring(lineStart, offset.coerceAtLeast(lineStart)).takeWhile { it == ' ' || it == '\t' }
}

/**
 * A tab if any line is tab-indented, else the smallest positive run of leading spaces. Code-action edits
 * bypass the editor's auto-indent, so emitted text must already match the file's style.
 */
internal fun detectIndentUnit(text: String): String {
	var minSpaces = Int.MAX_VALUE
	for (line in text.splitToSequence('\n')) {
		if (line.isEmpty()) continue
		if (line[0] == '\t') return "\t"
		if (line[0] != ' ') continue
		val trimmed = line.trimStart()
		// A block-comment continuation (` * text`, ` */`) is alignment, not indentation, and its single
		// leading space would otherwise win the minimum on virtually every real Java file.
		if (trimmed.startsWith('*')) continue
		val spaces = line.length - trimmed.length
		// A one-space indent unit is not a real style, so it can only be a line this scan misread.
		if (spaces in 2 until minSpaces) minSpaces = spaces
	}
	return if (minSpaces == Int.MAX_VALUE) "\t" else " ".repeat(minSpaces)
}

/** CRLF only when the file already uses it, so the edit does not mix line endings. */
internal fun detectNewline(text: String): String = if (text.contains("\r\n")) "\r\n" else "\n"

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
