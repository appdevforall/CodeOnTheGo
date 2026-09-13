package com.itsaky.androidide.lsp.kotlin.utils.refactor

import com.itsaky.androidide.lsp.refactor.RewriteSpan
import com.itsaky.androidide.lsp.refactor.TextSpan
import com.itsaky.androidide.lsp.refactor.detectNewline
import com.itsaky.androidide.lsp.refactor.existingBlockRewrite
import com.itsaky.androidide.lsp.refactor.replaceOccurrences
import com.itsaky.androidide.lsp.refactor.startOfWhitespaceBefore
import com.itsaky.androidide.lsp.refactor.wrapInBracesRewrite

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
		is AnchorForm.ExistingBlock -> existingBlockRewrite(fileText, form.block, targets, declaration, name)
		is AnchorForm.WrapInBraces -> wrapInBracesRewrite(fileText, form.body, targets, declaration, name)
		is AnchorForm.ConvertExpressionBody -> convertExpressionBodyRewrite(fileText, form, targets, declaration, name)
	}
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
