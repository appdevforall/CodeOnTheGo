package com.itsaky.androidide.lsp.java.refactor

import com.itsaky.androidide.lsp.refactor.RewriteSpan
import com.itsaky.androidide.lsp.refactor.TextSpan
import com.itsaky.androidide.lsp.refactor.detectIndentUnit
import com.itsaky.androidide.lsp.refactor.detectNewline
import com.itsaky.androidide.lsp.refactor.leadingIndentAt

/**
 * The two replacements an extraction performs: the new method, and the call that replaces the region.
 *
 * **Descending document order is mandatory, not stylistic.** `IDELanguageClientImpl.applyActionEdits`
 * iterates the list and applies each edit with line/column ranges against whatever the text is at that
 * moment, so an earlier edit must never shift a later one. In Java the new method always leads: the
 * anchor member *contains* the region, so its end is always past the region's.
 *
 * Nothing on that path calls `beginBatchEdit`, so this costs the user **two** undo steps and the
 * intermediate state does not compile. ADFA-5081 fixes that by batching the edit loop; until it lands
 * the two-step undo is a stated limitation.
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
	// The anchor member contains the region, so anything else means the plan and the text disagree.
	if (candidate.insertOffset < span.end) return null

	val newline = detectNewline(fileText)
	val indent = candidate.insertIndent
	val bodyIndent = indent + detectIndentUnit(fileText)
	val regionText = fileText.substring(span.start, span.end)
	val baseIndent = leadingIndentAt(fileText, span.start)

	val lines = indentedBodyLines(regionText, span.start, baseIndent, bodyIndent, newline, candidate.textBlockSpans)
	val bodyLines =
		when (val body = candidate.body) {
			is ExtractedBody.ExpressionBody -> {
				// An expression carries no `;` of its own -- the source one sits outside its span -- so the
				// statement it becomes gets one here.
				val returned =
					if (body.needsReturn) {
						// The first line is never inside a text block's interior -- the region starts at the
						// code itself -- so it always carries bodyIndent and `return ` goes straight after it.
						listOf(bodyIndent + "return " + lines.first().substring(bodyIndent.length)) + lines.drop(1)
					} else {
						lines
					}
				returned.dropLast(1) + (returned.last() + ";")
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

	val call = "$name(${candidate.parameters.joinToString(", ") { it.name }})"
	val callText =
		when (val form = candidate.callSite) {
			CallSiteForm.Call -> call
			CallSiteForm.CallStatement -> "$call;"
			is CallSiteForm.AssignOutput -> "${form.typeText} ${form.name} = $call;"
			CallSiteForm.Return -> "return $call;"
		}

	return listOf(
		RewriteSpan(TextSpan(candidate.insertOffset, candidate.insertOffset), newline + newline + declaration),
		RewriteSpan(span, callText),
	).sortedByDescending { it.span.start }
}

/**
 * The region's lines at the new method's body indentation: the original base indentation removed and
 * [bodyIndent] put in its place. Lines nested deeper than the base keep the extra depth; the first line
 * only gains the indent, since the span starts at the code itself.
 *
 * A line inside one of [protectedSpans] is emitted byte-for-byte. Those are text block literals, whose
 * interior whitespace is part of their value and whose closing delimiter sets the incidental-whitespace
 * margin, so moving either edits the interior of the moved code (ADR 0014).
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
