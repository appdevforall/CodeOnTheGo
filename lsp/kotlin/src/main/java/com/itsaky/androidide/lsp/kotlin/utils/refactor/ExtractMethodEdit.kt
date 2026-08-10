package com.itsaky.androidide.lsp.kotlin.utils.refactor

/**
 * The two replacements an extraction performs: the new function, then the call that replaces the
 * region.
 *
 * **The order is mandatory, not stylistic.** `IDELanguageClientImpl.applyActionEdits` iterates the
 * list and applies each edit with line/column ranges against whatever the text is at that moment.
 * The insertion point sits after the region, so emitting the call first would shift it and corrupt
 * the file. Descending document order is the only safe order.
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
	if (candidate.insertOffset > fileText.length || candidate.insertOffset < span.end) return null

	val newline = detectNewline(fileText)
	val indent = candidate.insertIndent
	val bodyIndent = indent + detectIndentUnit(fileText)
	val regionText = fileText.substring(span.start, span.end)
	val baseIndent = leadingIndentAt(fileText, span.start)

	val bodyLines =
		when (val body = candidate.body) {
			is ExtractedBody.ExpressionBody -> {
				val lines = reindent(regionText, baseIndent, newline)
				if (body.needsReturn) listOf("return " + lines.first()) + lines.drop(1) else lines
			}

			is ExtractedBody.StatementBody -> {
				reindent(regionText, baseIndent, newline) + listOfNotNull(body.trailingReturn)
			}
		}

	val declaration =
		buildString {
			// A blank line separates the new function from the declaration it follows.
			append(newline).append(newline)
			append(indent).append(candidate.signatureText(name)).append(" {").append(newline)
			bodyLines.forEach { append(bodyIndent).append(it).append(newline) }
			append(indent).append('}')
		}

	val call = "$name(${candidate.parameters.joinToString(", ") { it.name }})"
	val callText =
		when (val form = candidate.callSite) {
			CallSiteForm.Call -> call
			is CallSiteForm.AssignOutput -> "val ${form.name} = $call"
			CallSiteForm.Return -> "return $call"
		}

	return listOf(
		RewriteSpan(TextSpan(candidate.insertOffset, candidate.insertOffset), declaration),
		RewriteSpan(span, callText),
	)
}

/**
 * Splits the region into lines with its original base indentation removed, so the caller can prefix
 * each with the new function's body indentation. Lines nested deeper than the base keep the extra
 * depth; the first line never carries indentation, since the span starts at the code itself.
 */
private fun reindent(
	text: String,
	baseIndent: String,
	newline: String,
): List<String> =
	text.split(newline).mapIndexed { index, line ->
		if (index == 0) line else line.removePrefix(baseIndent)
	}
