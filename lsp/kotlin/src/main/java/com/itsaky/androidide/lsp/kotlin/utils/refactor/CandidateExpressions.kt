package com.itsaky.androidide.lsp.kotlin.utils.refactor

import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiWhiteSpace
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtAnonymousInitializer
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtBreakExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtContinueExpression
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtDeclarationWithBody
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry
import org.jetbrains.kotlin.psi.KtLoopExpression
import org.jetbrains.kotlin.psi.KtOperationReferenceExpression
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtReturnExpression
import org.jetbrains.kotlin.psi.KtStringTemplateEntry
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtSuperExpression
import org.jetbrains.kotlin.psi.KtSuperTypeListEntry
import org.jetbrains.kotlin.psi.KtThrowExpression

/** How many candidate expressions are ever offered. Keeps the chooser scannable on a phone. */
const val MAX_CANDIDATES = 3

/**
 * The purely syntactic result of resolving a cursor or selection to extraction targets.
 *
 * [expressions] is innermost-first and at most [MAX_CANDIDATES] long. [selectionMatchedInnermost] is
 * true when the caller passed a non-empty selection whose trimmed range is exactly the innermost
 * candidate's range -- the user has already said which expression they mean, so the UI can skip
 * asking.
 */
data class CandidateSyntax(
	val expressions: List<KtExpression>,
	val selectionMatchedInnermost: Boolean,
) {
	companion object {
		val NONE = CandidateSyntax(emptyList(), selectionMatchedInnermost = false)
	}
}

/**
 * Resolves `[selectionStart, selectionEnd)` in [file] to candidate expressions. A cursor is the
 * degenerate case where the two offsets are equal, so callers need only one code path.
 *
 * The selection is whitespace-trimmed first, because a touch-screen selection routinely carries a
 * leading or trailing space. From the resulting innermost element the parent chain is walked
 * outwards, keeping legal targets ([isLegalExtractionTarget]) and stopping at the enclosing
 * declaration. Blocks and other illegal nodes along the way are skipped rather than terminating the
 * walk, so `if (c) a else b` is still offered from inside one of its branches.
 *
 * Returns [CandidateSyntax.NONE] when the position cannot host an extraction at all -- see
 * [isExtractionPosition].
 */
fun candidateExpressionsAt(
	file: KtFile,
	selectionStart: Int,
	selectionEnd: Int,
): CandidateSyntax {
	val text = file.text
	val (start, end) = trimToCode(text, selectionStart, selectionEnd) ?: return CandidateSyntax.NONE

	val anchor = innermostElementFor(file, start, end) ?: return CandidateSyntax.NONE
	if (!isExtractionPosition(anchor)) return CandidateSyntax.NONE

	val collected = mutableListOf<KtExpression>()
	val seen = mutableSetOf<Pair<Int, Int>>()
	var element: PsiElement? = anchor
	while (element != null && element !is KtFile) {
		if (element is KtDeclaration && element !is KtFunctionLiteral) break
		if (element is KtExpression && element.isLegalExtractionTarget()) {
			val range = element.textRange.startOffset to element.textRange.endOffset
			if (seen.add(range)) {
				collected += element
				if (collected.size == MAX_CANDIDATES) break
			}
		}
		element = element.parent
	}

	if (collected.isEmpty()) return CandidateSyntax.NONE

	val innermost = collected.first().textRange
	val matched =
		selectionStart != selectionEnd &&
			innermost.startOffset == start &&
			innermost.endOffset == end
	return CandidateSyntax(collected, matched)
}

/**
 * Trims whitespace off both ends of `[start, end)`.
 *
 * A selection holding nothing but whitespace collapses to a cursor at [start] rather than yielding
 * nothing: a drag over the gap between two tokens carries the same intent as a tap in it, and the
 * cursor path already resolves a position resting just past a token. Returns null only when the range
 * is not a valid range into [text]. A cursor (start == end) is returned unchanged.
 */
internal fun trimToCode(
	text: String,
	start: Int,
	end: Int,
): Pair<Int, Int>? {
	if (start < 0 || end > text.length || start > end) return null
	if (start == end) return start to end
	var s = start
	var e = end
	while (s < e && text[s].isWhitespace()) s++
	while (e > s && text[e - 1].isWhitespace()) e--
	return if (s == e) start to start else s to e
}

/**
 * The innermost element covering `[start, end)`. For a cursor, [KtFile.findElementAt] is tried at
 * the offset and then just before it, so a caret sitting immediately after a token still resolves.
 */
private fun innermostElementFor(
	file: KtFile,
	start: Int,
	end: Int,
): PsiElement? {
	if (start == end) {
		val at = file.findElementAt(start)?.takeUnless { it is PsiWhiteSpace }
		val before = file.findElementAt((start - 1).coerceAtLeast(0))?.takeUnless { it is PsiWhiteSpace }
		return at ?: before
	}
	val first = file.findElementAt(start) ?: return null
	val last = file.findElementAt(end - 1) ?: return null
	return PsiTreeUtil.findCommonParent(first, last)
}

/**
 * Whether [element] sits somewhere an extraction can legally be anchored.
 *
 * Rejects the positions where no `val` can precede the expression:
 * - **annotation arguments** -- must be compile-time constants;
 * - **default parameter values** -- evaluated per call, and a hoisted local would not be in scope;
 * - **super-constructor delegation arguments** -- nothing can precede them;
 * - **anything outside an executable body** -- notably a class-body property initializer, which has
 *   no block to insert into. Converting one to a getter would change compute-once into
 *   compute-per-access, so it is declined instead.
 */
internal fun isExtractionPosition(element: PsiElement): Boolean {
	if (PsiTreeUtil.getParentOfType(element, KtAnnotationEntry::class.java, false) != null) return false
	if (PsiTreeUtil.getParentOfType(element, KtSuperTypeListEntry::class.java, false) != null) return false

	val parameter = PsiTreeUtil.getParentOfType(element, KtParameter::class.java, false)
	if (parameter != null && parameter.defaultValue?.isAncestorOf(element) == true) return false

	return enclosingExecutableBody(element) != null
}

/**
 * The nearest enclosing thing with a body that can hold statements: a lambda, a named or anonymous
 * function, a property accessor, an `init` block, or a constructor. Null when [element] is not
 * inside any of them.
 */
internal fun enclosingExecutableBody(element: PsiElement): PsiElement? {
	var current: PsiElement? = element
	while (current != null && current !is KtFile) {
		if (current is KtFunctionLiteral) return current
		if (current is KtDeclarationWithBody && current.bodyExpression?.isAncestorOf(element) == true) return current
		if (current is KtAnonymousInitializer && current.body?.isAncestorOf(element) == true) return current
		current = current.parent
	}
	return null
}

private fun PsiElement.isAncestorOf(other: PsiElement): Boolean = PsiTreeUtil.isAncestor(this, other, false)

/**
 * Whether this expression is a thing whose value can be bound to a `val`.
 *
 * Excluded, and why:
 * - blocks, loops, `return`/`throw`/`break`/`continue` -- no useful value to bind;
 * - lambdas, literal and wrapper alike -- outside their call site the parameter types are gone;
 * - operator tokens and call callees (`foo` in `foo(x)`) -- fragments, not expressions;
 * - the selector of a qualified expression (`b` in `a.b`) -- only meaningful with its receiver;
 * - the left side of an assignment -- a write target, not a value;
 * - `super` -- not a value;
 * - **bare literals** (`1`, `"text"`) -- extracting them is pointless, and excluding them removes
 *   the only case where omitting a type annotation could change meaning (an `Int` literal where a
 *   `Long` is expected, or a bare `null` inferring `Nothing?`).
 */
internal fun KtExpression.isLegalExtractionTarget(): Boolean {
	if (this is KtBlockExpression) return false
	if (this is KtLoopExpression) return false
	if (this is KtReturnExpression || this is KtThrowExpression) return false
	if (this is KtBreakExpression || this is KtContinueExpression) return false
	if (this is KtOperationReferenceExpression) return false
	if (this is KtSuperExpression) return false
	if (this is KtFunctionLiteral) return false
	// The wrapper around the literal. A hoisted lambda loses the parameter types its call site was
	// supplying, so `{ it.length + 1 }` becomes uncompilable the moment it leaves the call.
	if (this is KtLambdaExpression) return false
	if (isBareLiteral()) return false

	val parent = parent
	if (parent is KtQualifiedExpression && parent.selectorExpression === this) return false
	if (parent is KtCallExpression && parent.calleeExpression === this) return false
	if (parent is KtBinaryExpression &&
		parent.operationToken == KtTokens.EQ &&
		parent.left === this
	) {
		return false
	}
	return true
}

/** A numeric/boolean/char/null literal, or a string with no interpolation. */
private fun KtExpression.isBareLiteral(): Boolean =
	when (this) {
		is KtConstantExpression -> true
		is KtStringTemplateExpression -> entries.all { it.isLiteralEntry() }
		else -> false
	}

private fun KtStringTemplateEntry.isLiteralEntry(): Boolean = this is KtLiteralStringTemplateEntry
