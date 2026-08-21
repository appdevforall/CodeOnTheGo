package com.itsaky.androidide.lsp.kotlin.utils.refactor

import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtAnonymousInitializer
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtContainerNodeForControlStructureBody
import org.jetbrains.kotlin.psi.KtDeclarationWithBody
import org.jetbrains.kotlin.psi.KtDoWhileExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtPropertyAccessor
import org.jetbrains.kotlin.psi.KtWhenEntry
import org.jetbrains.kotlin.psi.KtWhileExpression

/**
 * One rung of the legal scope chain, before occurrences are known.
 *
 * [scopeElement] is the PSI node that *is* the scope, used to decide whether a referenced
 * declaration lives inside it (see [truncateAtCeiling]). [searchRange] bounds the occurrence search
 * for this rung.
 */
data class ScopeFrame(
	val label: String,
	val scopeElement: PsiElement,
	val searchRange: TextSpan,
	val anchorForm: AnchorForm,
)

/**
 * Enumerates the scopes [candidate] could be hoisted into, innermost first.
 *
 * Walks outward from the candidate's own statement. Each rung is one of the three [AnchorForm]
 * shapes: a real block, a braceless statement position that needs braces, or an expression body that
 * needs converting. The walk stops after the enclosing **named function, accessor or `init` block**
 * body -- the ceiling agreed for this refactoring. A class body or file is never an anchor, so a
 * property initializer outside any executable body yields nothing (already rejected earlier by
 * [isExtractionPosition]).
 *
 * Lambda boundaries are *crossed* here: whether crossing is actually legal depends on what the
 * candidate references, which needs resolution, so it is applied afterwards by [truncateAtCeiling].
 */
fun enclosingScopeFrames(candidate: KtExpression): List<ScopeFrame> {
	val text = candidate.containingFile.text
	val frames = mutableListOf<ScopeFrame>()
	var inner: PsiElement = candidate

	while (true) {
		val parent = inner.parent ?: break
		if (parent is KtFile) break

		val frame = frameFor(inner, text)
		if (frame == null) {
			// Most nodes are not themselves anchorable -- a value argument, an argument list, a lambda
			// literal. Keep climbing rather than stopping, otherwise the chain would end at the first
			// such node and, in particular, a candidate inside a lambda could never be hoisted out of
			// it even when that is legal.
			inner = parent
			continue
		}

		frames += frame
		// A named function / accessor / init body is the ceiling: record it, then stop.
		if (isCeilingBody(frame.scopeElement)) break
		inner = frame.scopeElement.parent ?: break
	}
	return frames
}

/**
 * Drops the rungs that lie outside [ceiling] -- the innermost scope holding a declaration the
 * candidate references. Passing null keeps the whole chain (nothing scoped inside was referenced).
 *
 * This is what enforces "crossing a lambda boundary is allowed only when nothing lambda-scoped is
 * referenced": if the candidate uses `it` or a lambda parameter, the lambda body *is* the ceiling
 * and every outer rung disappears.
 */
fun truncateAtCeiling(
	frames: List<ScopeFrame>,
	ceiling: PsiElement?,
): List<ScopeFrame> {
	if (ceiling == null) return frames
	val kept = frames.takeWhile { PsiTreeUtil.isAncestor(ceiling, it.scopeElement, false) || it.scopeElement === ceiling }
	return kept.ifEmpty { frames.take(1) }
}

/**
 * Builds the rung whose scope directly contains [inner], or null when [inner] is not in a position
 * this refactoring anchors in.
 */
private fun frameFor(
	inner: PsiElement,
	text: String,
): ScopeFrame? {
	val parent = inner.parent ?: return null

	// A braceless control-structure body is wrapped in a container node, so the `if`/loop is the
	// grandparent, not the parent. Without unwrapping, no braceless body is ever detected and the
	// declaration silently hoists to the enclosing block instead of braces being added.
	val controlOwner = (parent as? KtContainerNodeForControlStructureBody)?.parent

	if (parent is KtBlockExpression) {
		return ScopeFrame(
			label = blockLabel(parent),
			scopeElement = parent,
			searchRange = parent.textRange.let { TextSpan(it.startOffset, it.endOffset) },
			anchorForm =
				AnchorForm.ExistingBlock(
					contentSpan = contentSpanOf(parent),
					statementSpans =
						parent.statements.map { TextSpan(it.textRange.startOffset, it.textRange.endOffset) },
				),
		)
	}

	val bracelessOwner = controlOwner ?: parent
	val bracelessLabel = bracelessOwnerLabel(inner, bracelessOwner)
	if (bracelessLabel != null) {
		val indent = leadingIndentAt(text, bracelessOwner.textRange.startOffset)
		val span = TextSpan(inner.textRange.startOffset, inner.textRange.endOffset)
		return ScopeFrame(
			label = bracelessLabel,
			scopeElement = inner,
			searchRange = span,
			anchorForm =
				AnchorForm.WrapInBraces(
					bodyStart = span.start,
					bodyEnd = span.end,
					indent = indent,
					innerIndent = indent + detectIndentUnit(text),
				),
		)
	}

	if (parent is KtDeclarationWithBody && parent.bodyExpression === inner && !parent.hasBlockBody()) {
		val assign = parent.equalsToken ?: return null
		val indent = leadingIndentAt(text, parent.textRange.startOffset)
		val span = TextSpan(inner.textRange.startOffset, inner.textRange.endOffset)
		return ScopeFrame(
			label = declarationLabel(parent),
			scopeElement = inner,
			searchRange = span,
			anchorForm =
				AnchorForm.ConvertExpressionBody(
					assignStart = assign.textRange.startOffset,
					bodyStart = span.start,
					bodyEnd = span.end,
					indent = indent,
					innerIndent = indent + detectIndentUnit(text),
					// Filled in by the caller, which has the resolved return type.
					needsReturn = true,
				),
		)
	}

	return null
}

/** True for the body of a named function, accessor or `init` block -- where the chain stops. */
private fun isCeilingBody(scopeElement: PsiElement): Boolean {
	val owner = scopeElement.parent ?: return false
	return when (owner) {
		is KtNamedFunction, is KtPropertyAccessor, is KtAnonymousInitializer -> true
		else -> false
	}
}

/**
 * The name shown for a block rung.
 *
 * A braceless *or* braced control-structure body is wrapped in a container node, so the `if`/loop is
 * the block's grandparent; without unwrapping, every braced branch reads as a generic "block".
 * `getThen()`/`getElse()` return the unwrapped body expression, never the container, so branch
 * identity is decided by checking if the container's parent matches what `then`/`else` point at
 * (by comparing `owner.then?.parent === container`).
 */
private fun blockLabel(block: KtBlockExpression): String {
	val parent = block.parent
	val container = parent as? KtContainerNodeForControlStructureBody
	return when (val owner = container?.parent ?: parent) {
		is KtNamedFunction -> "fun ${owner.name ?: "<anonymous>"}"
		is KtPropertyAccessor -> if (owner.isGetter) "getter" else "setter"
		is KtAnonymousInitializer -> "init block"
		is KtFunctionLiteral -> "lambda"
		is KtIfExpression -> if (owner.then?.parent === container) "if block" else "else block"
		is KtForExpression -> "for loop"
		is KtWhileExpression -> "while loop"
		is KtDoWhileExpression -> "do-while loop"
		is KtWhenEntry -> "when branch"
		else -> "block"
	}
}

private fun declarationLabel(declaration: KtDeclarationWithBody): String =
	when (declaration) {
		is KtNamedFunction -> "fun ${declaration.name ?: "<anonymous>"}"
		is KtPropertyAccessor -> if (declaration.isGetter) "getter" else "setter"
		else -> "body"
	}

/** A label when [inner] is a braceless body, else null. */
private fun bracelessOwnerLabel(
	inner: PsiElement,
	parent: PsiElement,
): String? =
	when (parent) {
		is KtIfExpression -> {
			if (parent.then === inner) {
				"if branch"
			} else if (parent.`else` === inner) {
				"else branch"
			} else {
				null
			}
		}

		is KtForExpression -> {
			if (parent.body === inner) "for body" else null
		}

		is KtWhileExpression -> {
			if (parent.body === inner) "while body" else null
		}

		is KtDoWhileExpression -> {
			if (parent.body === inner) "do-while body" else null
		}

		is KtWhenEntry -> {
			if (parent.expression === inner) "when branch" else null
		}

		else -> {
			null
		}
	}

/**
 * The region inside a block's braces.
 *
 * A function, `if` or loop body owns its braces, so they are trimmed off. A lambda body block does not
 * -- the braces and any `param ->` header belong to the enclosing function literal -- so its own range
 * already *is* the content, which is what keeps the header on the brace line when the block is
 * expanded. Ownership is decided structurally, by the block's parent, rather than by sniffing the
 * block's own text for a leading `{` and trailing `}`: a lambda body whose sole statement is itself a
 * lambda literal (`{ x -> { x + 1 } }`) has text that looks brace-owned, and sniffing it would trim off
 * that inner lambda's own braces and return its interior instead of the outer body's full content.
 */
internal fun contentSpanOf(block: KtBlockExpression): TextSpan {
	val range = block.textRange
	return if (block.parent is KtFunctionLiteral) {
		TextSpan(range.startOffset, range.endOffset)
	} else {
		TextSpan(range.startOffset + 1, range.endOffset - 1)
	}
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
 * One indentation level for [text], inferred from its own lines: a tab if any line is tab-indented,
 * otherwise the smallest positive run of leading spaces, defaulting to a tab (the project
 * convention). Code-action edits bypass the editor's auto-indent, so emitted text must already match
 * the file's style. Mirrors the detection in `ImplementMembersAction`.
 */
internal fun detectIndentUnit(text: String): String {
	var minSpaces = Int.MAX_VALUE
	for (line in text.splitToSequence('\n')) {
		if (line.isEmpty()) continue
		if (line[0] == '\t') return "\t"
		if (line[0] != ' ') continue
		val spaces = line.takeWhile { it == ' ' }.length
		if (spaces in 1 until minSpaces) minSpaces = spaces
	}
	return if (minSpaces == Int.MAX_VALUE) "\t" else " ".repeat(minSpaces)
}
