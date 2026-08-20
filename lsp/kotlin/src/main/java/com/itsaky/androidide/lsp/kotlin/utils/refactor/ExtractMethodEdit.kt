package com.itsaky.androidide.lsp.kotlin.utils.refactor

/**
 * The two replacements an extraction performs: the new function, and the call that replaces the
 * region.
 *
 * **Descending document order is mandatory, not stylistic.** `IDELanguageClientImpl.applyActionEdits`
 * iterates the list and applies each edit with line/column ranges against whatever the text is at
 * that moment, so an earlier edit must never shift a later one. The result is therefore sorted by
 * descending start offset rather than assuming which comes first: a member or top-level target is
 * inserted *after* its anchor and leads the list, but a **local function must be declared before it
 * is called**, so that insertion precedes the region and the call site leads instead.
 *
 * Nothing on that path calls `beginBatchEdit`, so this costs the user **two** undo steps and the
 * intermediate state does not compile. ADFA-5081 fixes that by batching the edit loop; until it
 * lands the two-step undo is a stated limitation.
 *
 * The region is the only site rewritten (R13). Exact-duplicate matching would almost never fire, and
 * near-duplicate matching needs anti-unification plus a per-site parameter mapping.
 *
 * Returns null when the offsets cannot be honoured, which the caller reports rather than applying.
 */
fun buildExtractMethodRewrites(
	fileText: String,
	candidate: ExtractMethodCandidate,
	name: String,
): List<RewriteSpan>? {
	val span = candidate.span
	if (span.end > fileText.length) return null
	if (candidate.insertOffset > fileText.length) return null
	// Either side of the region is fine; inside it is incoherent -- the two edits would overlap.
	if (candidate.insertOffset > span.start && candidate.insertOffset < span.end) return null

	val newline = detectNewline(fileText)
	val indent = candidate.insertIndent
	val bodyIndent = indent + detectIndentUnit(fileText)
	val regionText = fileText.substring(span.start, span.end)
	val baseIndent = leadingIndentAt(fileText, span.start)

	val lines = indentedBodyLines(regionText, span.start, baseIndent, bodyIndent, newline, candidate.rawStringSpans)
	val bodyLines =
		when (val body = candidate.body) {
			is ExtractedBody.ExpressionBody -> {
				// The first line is never inside a literal's interior -- the region starts at the code
				// itself -- so it always carries bodyIndent and `return ` goes straight after it.
				if (body.needsReturn) {
					listOf(bodyIndent + "return " + lines.first().substring(bodyIndent.length)) + lines.drop(1)
				} else {
					lines
				}
			}

			is ExtractedBody.StatementBody -> {
				lines + listOfNotNull(body.trailingReturn?.let { bodyIndent + it })
			}
		}

	val declaration =
		buildString {
			append(indent).append(candidate.signatureText(name)).append(" {").append(newline)
			bodyLines.forEach { append(it).append(newline) }
			append(indent).append('}')
		}

	// A blank line separates the new function from its neighbour either way. Inserting before the
	// anchor starts at the anchor's own line, whose indentation is already in the file ahead of the
	// insertion point -- so that first indent is dropped here and put back in front of the anchor.
	val insertionText =
		if (candidate.insertOffset <= span.start) {
			declaration.removePrefix(indent) + newline + newline + indent
		} else {
			newline + newline + declaration
		}

	val call = "$name(${candidate.parameters.joinToString(", ") { it.name }})"
	val callText =
		when (val form = candidate.callSite) {
			CallSiteForm.Call -> call
			is CallSiteForm.AssignOutput -> "val ${form.name} = $call"
			CallSiteForm.Return -> "return $call"
		}

	return listOf(
		RewriteSpan(TextSpan(candidate.insertOffset, candidate.insertOffset), insertionText),
		RewriteSpan(span, callText),
	).sortedByDescending { it.span.start }
}

/**
 * The region's lines at the new function's body indentation: the original base indentation removed and
 * [bodyIndent] put in its place. Lines nested deeper than the base keep the extra depth; the first line
 * only gains the indent, since the span starts at the code itself.
 *
 * A line inside one of [protectedSpans] is emitted byte-for-byte. Those are multi-line string literals,
 * whose interior whitespace is part of their value, and whose closing delimiter sets `trimIndent`'s
 * margin -- moving either edits the interior of the moved code (ADR 0013).
 */
private fun indentedBodyLines(
	regionText: String,
	regionStart: Int,
	baseIndent: String,
	bodyIndent: String,
	newline: String,
	protectedSpans: List<TextSpan>,
): List<String> {
	var offset = regionStart
	return regionText.split(newline).mapIndexed { index, line ->
		val lineStart = offset
		offset += line.length + newline.length
		when {
			index == 0 -> bodyIndent + line
			protectedSpans.any { lineStart > it.start && lineStart < it.end } -> line
			else -> bodyIndent + line.removePrefix(baseIndent)
		}
	}
}
