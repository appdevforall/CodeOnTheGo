package com.itsaky.androidide.lsp.kotlin.utils.refactor

/**
 * The edits one inline performs: one replacement per inlined reference plus, when the declaration is
 * deleted, one for the declaration.
 *
 * **Descending document order is mandatory, not stylistic.** `IDELanguageClientImpl.applyActionEdits`
 * iterates the list and applies each edit with line/column ranges against whatever the text is at
 * that moment, so an earlier edit must never shift a later one. The declaration precedes every
 * reference, so its deletion always sorts last. Spans never overlap: references are distinct reads and
 * the declaration's span contains none of them.
 *
 * Nothing on that path calls `beginBatchEdit`, so an inline over N references costs the user **N+1**
 * undo steps and the intermediate states do not compile. A follow-up change will batch these edits
 * into one undo step; collapsing everything into one spanning replacement here was rejected in favor
 * of the per-reference edit list described above.
 *
 * Returns null when there is nothing to rewrite or the offsets cannot be honoured, which the caller
 * reports rather than applying.
 */
fun buildInlineVariableRewrites(
	plan: InlineVariablePlan,
	mode: InlineMode,
): List<RewriteSpan>? {
	val text = plan.fileText
	val targets =
		when (mode) {
			InlineMode.ThisReferenceOnly -> {
				listOfNotNull(plan.references.getOrNull(plan.cursorReferenceIndex)?.takeIf { it.isInlinable })
			}

			InlineMode.AllReferences -> {
				plan.inlinableReferences
			}
		}
	if (targets.isEmpty()) return null
	if (targets.any { it.span.end > text.length || it.span.start < 0 }) return null
	if (plan.declarationSpan.end > text.length) return null

	val substitutions = targets.map { RewriteSpan(it.span, substitutionTextFor(plan, it)) }
	val deletion =
		if (mode == InlineMode.AllReferences && plan.canDeleteDeclaration) declarationDeletion(plan) else null

	return (substitutions + listOfNotNull(deletion)).sortedByDescending { it.span.start }
}

/**
 * The initializer's text as it lands at one reference.
 *
 * Inside a short-form template entry the braces do the delimiting, so the parenthesisation is not
 * applied on top: `"total: ${a + b}"`, never `"total: ${(a + b)}"`. The short form survives only for a
 * plain identifier, because `$user.name` means `user.toString() + ".name"`.
 */
internal fun substitutionTextFor(
	plan: InlineVariablePlan,
	reference: InlineReference,
): String {
	val value = plan.initializerText
	if (reference.isShortTemplateEntry) {
		return if (isPlainIdentifier(value)) "\$" + value else "\${" + value + "}"
	}
	return if (plan.initializerNeedsParentheses) "($value)" else value
}

/**
 * Keywords that read as identifiers but are not: `"$true"` does not parse, so the braced form is the
 * only way to substitute one.
 *
 * `this` is deliberately absent -- `"$this"` is legal Kotlin, the one keyword the short form accepts.
 */
private val KEYWORDS_REJECTED_AFTER_DOLLAR = setOf("true", "false", "null")

/** Whether [text] is a bare Kotlin identifier, and so legal after a `$` in a template. */
internal fun isPlainIdentifier(text: String): Boolean {
	if (text.isEmpty()) return false
	if (text in KEYWORDS_REJECTED_AFTER_DOLLAR) return false
	if (!(text[0].isLetter() || text[0] == '_')) return false
	return text.all { it.isLetterOrDigit() || it == '_' }
}

/**
 * The deletion of the declaration, in one of three line shapes -- all pure span arithmetic.
 *
 * Real code on the line means only the declaration's own span goes (plus a following `;` and one
 * space): deleting "the line" would take the `return` or the closing brace with it. A trailing comment
 * is preserved on its own line at the declaration's indentation, because a comment left describing
 * nothing is visible and removed with one gesture, while a deleted comment is invisible.
 */
private fun declarationDeletion(plan: InlineVariablePlan): RewriteSpan {
	val text = plan.fileText
	val span = plan.declarationSpan
	val lineStart = lineStartOffset(text, span.start)
	val lineEnd = endOfLineContent(text, span.end)
	val prefix = text.substring(lineStart, span.start)
	val suffix = text.substring(span.end, lineEnd).trim()

	if (prefix.isNotBlank() || !(suffix.isEmpty() || isWholeLineComment(suffix))) {
		var end = span.end
		if (text.startsWith(";", end)) end++
		if (text.startsWith(" ", end)) end++
		return RewriteSpan(TextSpan(span.start, end), "")
	}

	val newline = detectNewline(text)
	val replacement = if (suffix.isEmpty()) "" else leadingIndentAt(text, span.start) + suffix + newline
	return RewriteSpan(TextSpan(lineStart, endOfLineWithTerminator(text, lineEnd)), replacement)
}

/** Whether what follows the declaration on its line is only a comment. */
private fun isWholeLineComment(suffix: String): Boolean = suffix.startsWith("//") || (suffix.startsWith("/*") && suffix.endsWith("*/"))

/** The offset of the line terminator at or after [offset], or the end of the text. */
private fun endOfLineContent(
	text: String,
	offset: Int,
): Int {
	var index = offset.coerceIn(0, text.length)
	while (index < text.length && text[index] != '\n') index++
	// A CRLF file must not leave its lone `\r` behind as line content.
	return if (index > offset && text[index - 1] == '\r') index - 1 else index
}

/** [offset] advanced past the line terminator, so the deletion leaves no blank line. */
private fun endOfLineWithTerminator(
	text: String,
	offset: Int,
): Int {
	var index = offset.coerceIn(0, text.length)
	if (index < text.length && text[index] == '\r') index++
	if (index < text.length && text[index] == '\n') index++
	return index
}
