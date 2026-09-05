package com.itsaky.androidide.lsp.java.refactor

import com.itsaky.androidide.lsp.refactor.RewriteSpan
import com.itsaky.androidide.lsp.refactor.TextSpan
import com.itsaky.androidide.lsp.refactor.detectNewline
import com.itsaky.androidide.lsp.refactor.existingBlockRewrite
import com.itsaky.androidide.lsp.refactor.replaceOccurrences
import com.itsaky.androidide.lsp.refactor.wrapInBracesRewrite

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
		is AnchorForm.ExistingBlock -> existingBlockRewrite(fileText, form.block, targets, declaration, name)
		is AnchorForm.WrapInBraces -> wrapInBracesRewrite(fileText, form.body, targets, declaration, name)
		is AnchorForm.ConvertExpressionBody -> convertExpressionBodyRewrite(fileText, form, targets, declaration, name)
	}
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
