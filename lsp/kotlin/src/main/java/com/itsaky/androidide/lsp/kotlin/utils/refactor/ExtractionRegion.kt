package com.itsaky.androidide.lsp.kotlin.utils.refactor

import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile

/**
 * What a selection resolved to. Exactly two kinds, which is the whole reason the hard cases never
 * arise: a selection covering half an `if` and half its `else`, or straddling a lambda boundary,
 * is neither, and is declined by construction rather than filtered out later.
 */
sealed interface ExtractionRegion {
	/** The region's covering span in the file's text. */
	val span: TextSpan

	/**
	 * One or more nested expressions at the cursor, innermost first. The user picks between them in
	 * the sheet unless [selectionMatchedInnermost] says they already have.
	 */
	data class Expressions(
		val candidates: List<KtExpression>,
		val selectionMatchedInnermost: Boolean,
	) : ExtractionRegion {
		override val span: TextSpan
			get() = candidates.first().textRange.let { TextSpan(it.startOffset, it.endOffset) }
	}

	/** One or more sibling statements in a single [block]. */
	data class Statements(
		val statements: List<KtExpression>,
		val block: KtBlockExpression,
	) : ExtractionRegion {
		override val span: TextSpan
			get() =
				TextSpan(
					statements.first().textRange.startOffset,
					statements.last().textRange.endOffset,
				)
	}
}

/**
 * Resolves `[selectionStart, selectionEnd)` to the one region the refactoring will act on, or null
 * when it is neither kind.
 *
 * A bare cursor is always the expression path. A non-empty selection snaps **outward** to whole
 * statements -- a touch selection will not land on a boundary. When the snapped range is a single
 * statement and the selection sits strictly inside it, the expression path is preferred instead:
 * that is what the user's selection actually points at, not the enclosing statement. But if nothing
 * there is a legal expression target, the snapped statement is used anyway -- a near-miss drag
 * (e.g. selecting `sum = a + b` and missing the leading `val`) should still extract something,
 * rather than being refused for landing a few characters short.
 */
fun resolveExtractionRegion(
	file: KtFile,
	selectionStart: Int,
	selectionEnd: Int,
): ExtractionRegion? {
	val (start, end) = trimToCode(file.text, selectionStart, selectionEnd) ?: return null
	if (start == end) return expressionRegion(file, selectionStart, selectionEnd)

	val range = snapToStatements(file, start, end) ?: return expressionRegion(file, selectionStart, selectionEnd)

	val only = range.statements.singleOrNull()
	if (only != null && (start > only.textRange.startOffset || end < only.textRange.endOffset)) {
		expressionRegion(file, selectionStart, selectionEnd)?.let { return it }
	}

	return ExtractionRegion.Statements(range.statements, range.block)
}

private fun expressionRegion(
	file: KtFile,
	selectionStart: Int,
	selectionEnd: Int,
): ExtractionRegion.Expressions? {
	val syntax = candidateExpressionsAt(file, selectionStart, selectionEnd)
	if (syntax.expressions.isEmpty()) return null
	return ExtractionRegion.Expressions(syntax.expressions, syntax.selectionMatchedInnermost)
}

/** A run of sibling statements together with the [KtBlockExpression] that holds them. */
private class StatementRange(
	val statements: List<KtExpression>,
	val block: KtBlockExpression,
)

/**
 * The whole statements `[start, end)` touches, when they are siblings in one [KtBlockExpression].
 *
 * Null when the two ends land in different blocks, which is what rejects a selection spanning an
 * `if` body and the code after it without needing to reason about the constructs involved.
 */
private fun snapToStatements(
	file: KtFile,
	start: Int,
	end: Int,
): StatementRange? {
	// end > start is guaranteed by the start == end early-return in resolveExtractionRegion.
	val first = statementContaining(file, start) ?: return null
	val last = statementContaining(file, end - 1) ?: return null

	val block = first.parent as? KtBlockExpression ?: return null
	if (last.parent !== block) return null
	if (!isExtractionPosition(first)) return null

	val statements = block.statements
	val from = statements.indexOfFirst { it === first }
	val to = statements.indexOfFirst { it === last }
	if (from < 0 || to < from) return null
	return StatementRange(statements.subList(from, to + 1).toList(), block)
}

/**
 * The statement containing [offset]: the nearest ancestor that is a direct expression child of a
 * block. Null for a position that is not inside one, such as a comment or a class body.
 */
private fun statementContaining(
	file: KtFile,
	offset: Int,
): KtExpression? {
	var current: PsiElement? = file.findElementAt(offset) ?: return null
	while (current != null && current !is KtFile) {
		if (current is KtExpression && current.parent is KtBlockExpression) return current
		current = current.parent
	}
	return null
}
