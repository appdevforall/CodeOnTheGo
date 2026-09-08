package com.itsaky.androidide.lsp.refactor

/**
 * The geometry of a scope that already has a `{ ... }` body, which is where a declaration lands in the
 * overwhelming majority of extractions.
 *
 * [statementSpans] are the block's direct child statements, ascending. The anchor point is the first of
 * them containing the first served occurrence -- which is what makes an outer rung differ from an inner
 * one; anchoring on the occurrence's own line instead would make every rung of a chain produce the same
 * edit.
 *
 * [contentSpan] is the region *inside* the braces. It tells a block written on one line from a
 * multi-line one, where inserting at the statement's line start would put the declaration outside the
 * braces.
 *
 * Both languages describe a block this way, so the geometry and everything that reasons about it live
 * here rather than once per language server.
 */
data class BlockAnchor(
	val contentSpan: TextSpan,
	val statementSpans: List<TextSpan>,
)

/** A braceless statement position: `if (c) foo();`, a braceless loop body, a single-statement rule. */
data class BracelessBody(
	val bodyStart: Int,
	val bodyEnd: Int,
	val indent: String,
	val innerIndent: String,
)

/**
 * Shared by the planner and the rewriter so a rung is never *offered* that the rewrite would refuse --
 * otherwise the sheet opens, the user fills it in, and confirm fails with a generic error.
 */
sealed interface BlockPlacement {
	/** The declaration becomes a new line above [anchor], at [anchor]'s indentation. */
	data class LineAbove(
		val anchor: TextSpan,
	) : BlockPlacement

	/** The block is written on one line and is expanded, with the declaration inside its braces. */
	data object ExpandOneLine : BlockPlacement

	/** Neither is sound here, so the rung is declined. */
	data object Refused : BlockPlacement
}

/** The block statement holding [target], or null when the plan and the text disagree. */
fun anchorOf(
	block: BlockAnchor,
	target: TextSpan,
): TextSpan? = block.statementSpans.firstOrNull { it.start <= target.start && target.end <= it.end }

/**
 * Refuses two shapes: nothing in the block contains the target (plan and text disagree), or something
 * besides indentation precedes the anchor statement while the block's content spans several lines.
 *
 * The second is the interesting one. `items.forEach(x -> { log(x);\n\tlog(y); })` cannot take a new
 * line above `log(x)`, because that line start is before the opening brace -- outside the scope where a
 * lambda parameter exists. Neither can `it = src.iterator(); use(it.next());\n\ttail();`, where the
 * anchor shares its line with a statement the expression depends on and hoisting above it reorders
 * execution. Expanding is sound only when the whole block is that one line, because then re-emitting
 * its content loses nothing.
 *
 * [block]'s spans are substringed against [fileText] unchecked, so callers must pass the text those
 * spans were computed against -- the plan's own, never the live document.
 */
fun blockPlacementFor(
	fileText: String,
	block: BlockAnchor,
	firstTarget: TextSpan,
): BlockPlacement {
	val anchor = anchorOf(block, firstTarget) ?: return BlockPlacement.Refused
	val lineStart = lineStartOffset(fileText, anchor.start)

	val linePrefix = fileText.substring(lineStart, anchor.start)
	// Nothing but indentation in front: the declaration can take its own line above, which is the common
	// case and the only one where the anchor's line needs no rearranging.
	if (linePrefix.isBlank()) return BlockPlacement.LineAbove(anchor)

	val contentIsOneLine = !fileText.substring(block.contentSpan.start, block.contentSpan.end).contains('\n')
	return if (contentIsOneLine) BlockPlacement.ExpandOneLine else BlockPlacement.Refused
}

/**
 * A replace-all anchors on the *first* served occurrence, so a leading one whose statement shares the
 * opening-brace line would refuse the whole rewrite even though the user's own site is placeable.
 * [candidateSpan] is never dropped; only leading sites matter, since a later one never becomes anchor.
 *
 * [block] is null for a rung that is not a block, where every occurrence is servable.
 */
fun servableOccurrences(
	fileText: String,
	block: BlockAnchor?,
	occurrences: List<TextSpan>,
	candidateSpan: TextSpan,
): List<TextSpan> {
	if (block == null) return occurrences
	return occurrences.dropWhile {
		it != candidateSpan && blockPlacementFor(fileText, block, it) is BlockPlacement.Refused
	}
}

/**
 * Whether hoisting [span] to a rung would read a value the original never saw, because the hoist skips
 * a write.
 *
 * A write between the anchor statement and the candidate is simply skipped: extracting `limit + 1` from
 * `if (c) { limit = 5; foo(limit + 1); }` at the method rung anchors on the `if` and reads the
 * pre-assignment value.
 *
 * [scopeSpan] is the rung's own extent and [loops] the loop spans inside it. Shared by both language
 * planners: the test is pure span arithmetic, and keeping a copy per language is how the loop fixes
 * reached one half and not the other.
 */
fun hoistSkipsWrite(
	span: TextSpan,
	scopeSpan: TextSpan,
	block: BlockAnchor?,
	loops: List<TextSpan>,
	writes: List<Int>,
): Boolean {
	if (writes.isEmpty()) return false

	if (block != null) {
		val anchor = anchorOf(block, span)
		if (anchor != null && writes.any { it in anchor.start until span.start }) return true
	}

	return hoistsOverLoopWrite(span, scopeSpan, loops, writes)
}

/**
 * Whether [target] sits in a loop the rung does not, which a write inside that loop makes unsound:
 * `while (limit < 10) { foo(limit + 1); limit++; }` hoisted out of the loop evaluates once and feeds
 * every iteration the same value.
 *
 * Asked per served occurrence, not just about the candidate. An occurrence can sit in a loop the
 * candidate is not in at all -- `use(limit + 1); while (limit < 10) { use(limit + 1); limit++; }` -- and
 * folding that one freezes the loop.
 *
 * Loops are visited innermost-first, and the first one containing the rung ends the walk: from there
 * outwards the declaration re-runs with every iteration, so nothing is skipped.
 */
fun hoistsOverLoopWrite(
	target: TextSpan,
	scopeSpan: TextSpan,
	loops: List<TextSpan>,
	writes: List<Int>,
): Boolean {
	for (loop in loops.filter { it.start <= target.start && target.end <= it.end }.sortedBy { it.length }) {
		if (loop.start <= scopeSpan.start && scopeSpan.end <= loop.end) return false
		if (writes.any { it in loop.start until loop.end }) return true
	}
	return false
}

/**
 * Restricts [occurrences] to a contiguous run no write to a referenced mutable interrupts, since
 * `foo(limit + 1); limit = 5; foo(limit + 1);` is the same expression holding two different values.
 *
 * Unsound sites are excluded rather than warned about. The walk grows outward from the candidate, never
 * dropping the user's own site, and stops in each direction at the first write it would cross.
 *
 * The guarantee is bounded to **variable writes**, which is all a caller's write scan can see.
 * Collapsing repeated evaluations of an effectful expression is left alone deliberately -- it is what
 * Extract Variable means, and what every IDE does -- so `foo(items.size()); items.add(x);
 * bar(items.size());` does fold to one read. What this rules out is the case where the *same* text
 * provably names two different values.
 *
 * A write inside an occurrence's *own* span counts as well as one in the gaps between them: `use(i++);
 * use(i++);` increments twice and passes two values, so an expression that mutates what it reads can
 * never be folded with a second copy of itself. When the candidate is that expression, it is served
 * alone.
 */
fun excludeUnsoundOccurrences(
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

	fun writesWithin(span: TextSpan): Boolean = writeBetween(span.start, span.end)

	val candidate = ordered[candidateIndex]
	if (writesWithin(candidate)) return listOf(candidateSpan)

	val accepted = mutableListOf(candidate)
	for (i in candidateIndex - 1 downTo 0) {
		if (writesWithin(ordered[i]) || writeBetween(ordered[i].end, candidate.start)) break
		accepted.add(0, ordered[i])
	}
	for (i in candidateIndex + 1 until ordered.size) {
		if (writesWithin(ordered[i]) || writeBetween(candidate.end, ordered[i].start)) break
		accepted += ordered[i]
	}
	return accepted
}

/**
 * The anchor is the statement *of this scope* holding the first served occurrence, so picking an outer
 * rung hoists the declaration above the enclosing statement. The span ends at the last occurrence, so
 * untouched trailing code is left alone.
 *
 * Null when [blockPlacementFor] refuses the anchor; the caller reports that rather than guessing.
 */
fun existingBlockRewrite(
	fileText: String,
	block: BlockAnchor,
	targets: List<TextSpan>,
	declaration: String,
	name: String,
): RewriteSpan? {
	val last = targets.last()
	val anchor =
		when (val placement = blockPlacementFor(fileText, block, targets.first())) {
			is BlockPlacement.Refused -> {
				return null
			}

			is BlockPlacement.ExpandOneLine -> {
				return oneLineBlockRewrite(fileText, block, targets, declaration, name)
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
	block: BlockAnchor,
	targets: List<TextSpan>,
	declaration: String,
	name: String,
): RewriteSpan {
	val content = block.contentSpan
	val newline = detectNewline(fileText)
	val indent = leadingIndentAt(fileText, content.start)
	val innerIndent = indent + detectIndentUnit(fileText)

	// Widen over the whitespace on each side of the content so it does not survive the rewrite as a
	// stray "{ " or " }". A block that does not own its braces (a lambda body) stops short of them.
	val spanEnd = endOfWhitespaceAfter(fileText, content.end)
	val span = TextSpan(startOfWhitespaceBefore(fileText, content.start), spanEnd)

	// What follows the content is not always the owner's `}` at [indent]. A colon-form case group ends at
	// its last statement, and the next token is the enclosing switch's brace, one level further out; using
	// the case's own indent re-indents that brace so it no longer lines up with its `switch (`. The
	// whitespace being consumed already carries the following token's indent, so reuse it, and fall back
	// to [indent] when the content and the brace share a line and there is none to read.
	val consumed = fileText.substring(content.end, spanEnd)
	val lastNewline = consumed.lastIndexOf(newline)
	val trailingIndent = if (lastNewline >= 0) consumed.substring(lastNewline + newline.length) else indent

	// Anything already in front of the anchor stays in front of it: prepending the declaration to the
	// whole block would hoist it above statements the expression depends on.
	val anchorStart = anchorOf(block, targets.first())?.start?.coerceIn(content.start, content.end) ?: content.start
	val before = fileText.substring(content.start, anchorStart).trim()
	val body = replaceOccurrences(fileText, TextSpan(anchorStart, content.end), targets, name).trim()

	val newText =
		buildString {
			append(newline)
			if (before.isNotEmpty()) append(innerIndent).append(before).append(newline)
			append(innerIndent).append(declaration).append(newline)
			append(innerIndent).append(body).append(newline)
			append(trailingIndent)
		}
	return RewriteSpan(span = span, newText = newText)
}

/** Wraps a braceless statement in a block containing the declaration and the original statement. */
fun wrapInBracesRewrite(
	fileText: String,
	body: BracelessBody,
	targets: List<TextSpan>,
	declaration: String,
	name: String,
): RewriteSpan {
	// Occurrences in a braceless scope are confined to the statement itself (the frame's search range
	// *is* this span), so no cross-span targets are possible; replaceOccurrences filters anyway.
	val bodySpan = TextSpan(body.bodyStart, body.bodyEnd)
	val newline = detectNewline(fileText)
	val statement = replaceOccurrences(fileText, bodySpan, targets, name)

	// The brace takes the owner's indent, not the body's. A body on its own line sits one level in, so
	// emitting the brace where the body started leaves it deeper than the `}` that closes it; the span
	// absorbs that leading whitespace instead. A body sharing the owner's line (`if (c) foo();`) has
	// none to absorb and already opens in the right place.
	val lineStart = fileText.lastIndexOf(newline.last(), body.bodyStart - 1) + 1
	val ownsItsLine = lineStart < body.bodyStart && fileText.substring(lineStart, body.bodyStart).isBlank()
	val span = TextSpan(if (ownsItsLine) lineStart else body.bodyStart, body.bodyEnd)

	val newText =
		buildString {
			if (ownsItsLine) append(body.indent)
			append('{').append(newline)
			append(body.innerIndent).append(declaration).append(newline)
			append(body.innerIndent).append(statement).append(newline)
			append(body.indent).append('}')
		}
	return RewriteSpan(span, newText)
}
