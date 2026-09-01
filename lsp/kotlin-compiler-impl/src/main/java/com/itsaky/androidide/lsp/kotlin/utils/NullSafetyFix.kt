package com.itsaky.androidide.lsp.kotlin.utils

import com.itsaky.androidide.lsp.models.TextEdit
import org.jetbrains.kotlin.com.intellij.openapi.util.TextRange
import org.jetbrains.kotlin.com.intellij.psi.PsiFile
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression

/** FIR diagnostic factory name for an unsafe member access on a nullable receiver. */
internal const val UNSAFE_CALL_FACTORY = "UNSAFE_CALL"

/**
 * Returns [UNSAFE_CALL_FACTORY] when [factoryName] is the diagnostic the null-safety fixes apply to,
 * else null. Keyed on the factory name (plain data captured inside `analyze`) so the diagnostic's
 * `KaLifetimeOwner` never escapes. This is the single source of truth for the trigger: the diagnostic
 * provider stores its result, and the action gates visibility on it.
 */
internal fun nullSafetyFactoryFor(factoryName: String): String? = factoryName.takeIf { it == UNSAFE_CALL_FACTORY }

/** The distinct null-safety rewrites offered for one unsafe `receiver.selector` access. */
enum class NullSafetyKind {
	/** `receiver.selector` -> `receiver!!.selector` (assert the receiver is non-null). */
	ASSERT_NON_NULL,

	/** `receiver.selector` -> `receiver?.selector` (skip the call when the receiver is null). */
	SAFE_CALL,

	/** `receiver.selector` -> `(receiver ?: TODO()).selector` (fall back to a default receiver). */
	ELVIS,
}

data class NullSafetyVariant(
	val kind: NullSafetyKind,
	val edits: List<TextEdit>,
)

/** Placeholder fallback for the Elvis variant: `Nothing`, so it type-checks anywhere and forces the user to fill it in. */
private const val ELVIS_FALLBACK = "TODO()"

/**
 * Locates the `receiver.selector` access an UNSAFE_CALL diagnostic (whose PSI is the whole
 * [KtDotQualifiedExpression]) covers, given its [startOffset], [endOffset] in [file]. Returns null
 * when no dot-qualified expression spans exactly that range (e.g. a stale range after edits).
 */
internal fun findNullableMemberAccess(
	file: PsiFile,
	startOffset: Int,
	endOffset: Int,
): KtDotQualifiedExpression? = PsiTreeUtil.findElementOfClassAtRange(file, startOffset, endOffset, KtDotQualifiedExpression::class.java)

/**
 * Builds the three null-safety rewrites for [qe]. Each variant is a single, minimal, fully-formed
 * [TextEdit] (LSP code-action edits bypass the editor's auto-indent, so the emitted text must be
 * final). Must be called under `project.read`.
 *
 * The Elvis variant wraps only the receiver, keeping the top-level member access intact so the
 * result is valid in any surrounding context without extra parentheses.
 */
internal fun nullSafetyVariants(qe: KtDotQualifiedExpression): List<NullSafetyVariant> {
	val file = qe.containingFile
	val receiver = qe.receiverExpression
	val receiverRange = receiver.textRange
	val dotStart = qe.operationTokenNode.textRange.startOffset

	return listOf(
		NullSafetyVariant(
			NullSafetyKind.ASSERT_NON_NULL,
			listOf(insertAt(receiverRange.endOffset, "!!", file)),
		),
		NullSafetyVariant(
			NullSafetyKind.SAFE_CALL,
			listOf(insertAt(dotStart, "?", file)),
		),
		NullSafetyVariant(
			NullSafetyKind.ELVIS,
			listOf(replace(receiverRange, "(${receiver.text} ?: $ELVIS_FALLBACK)", file)),
		),
	)
}

private fun insertAt(
	offset: Int,
	text: String,
	file: PsiFile,
): TextEdit = TextEdit(TextRange(offset, offset).toRange(file), text)

private fun replace(
	range: TextRange,
	text: String,
	file: PsiFile,
): TextEdit = TextEdit(range.toRange(file), text)
