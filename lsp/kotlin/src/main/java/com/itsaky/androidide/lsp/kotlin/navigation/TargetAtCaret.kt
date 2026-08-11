package com.itsaky.androidide.lsp.kotlin.navigation

import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("TargetAtCaret")

/**
 * What a caret names, for a feature that starts *from* a declaration rather than navigating to one.
 *
 * Find usages can be invoked from either end - on the declaration itself, or on any reference to it -
 * and the two need different resolution, so the distinction is made once here rather than re-derived
 * by a type test later.
 */
internal sealed interface CaretTarget {
	/** The caret sits on [declaration]'s own name identifier. Its symbol is the search target. */
	data class Declaration(
		val declaration: KtNamedDeclaration,
	) : CaretTarget

	/** The caret sits on a reference. Resolving [element] yields the search target. */
	data class Reference(
		val element: KtElement,
	) : CaretTarget
}

/**
 * What the caret at [offset] in [file] names, or null when it names nothing.
 *
 * Declaration-first: a caret on a declaration's own name targets *that declaration*, and only a caret
 * that names nothing declarable is interpreted as a reference. The order is observable for a
 * destructuring entry, which is both at once - `x` in `val (x, y) = p` targets the local `x` here,
 * while go-to-definition navigates from the same caret to `component1`.
 *
 * Callers must hold the project read lock. Pure PSI: no analysis session is needed or used.
 */
internal fun targetAtCaret(
	file: KtFile,
	offset: Int,
): CaretTarget? {
	declarationAtCaret(file, offset)?.let { return CaretTarget.Declaration(it) }

	// Not a declaration's name, so fall back to go-to-definition's reference lookup, which repeats the
	// leaf lookup above. One extra findElementAt is worth leaving that helper's contract untouched:
	// it must keep returning null for a declaration's own name, which is the caret we just handled.
	return referenceAtCaret(file, offset)?.let(CaretTarget::Reference)?.also {
		logger.debug("Caret at {} in {} names a reference", offset, file.name)
	}
}

/**
 * The declaration whose own name the caret at [offset] sits on, or null.
 *
 * Both candidate leaves are tried, not just the first navigable one. `referenceAtCaret` can stop at
 * the first, because it retries only when the primary leaf names nothing at all; here the primary
 * leaf can be navigable in its own right and still not be a name - a caret just past `fun target`
 * lands on `(`, which is navigable for the invoke convention. Checking only that leaf would make a
 * caret one character past a declaration's name find nothing.
 */
private fun declarationAtCaret(
	file: KtFile,
	offset: Int,
): KtNamedDeclaration? =
	(
		declarationNamedBy(navigableLeafAt(file, offset))
			?: declarationNamedBy(navigableLeafAt(file, (offset - 1).coerceAtLeast(0)))
	)?.also {
		logger.debug("Caret at {} in {} names declaration '{}'", offset, file.name, it.name)
	}

/**
 * The declaration [leaf] is the name identifier of, or null.
 *
 * The identity check is what makes this precise: every caret has some enclosing declaration - a call
 * site's nearest one is the function containing it - so proximity alone would target the container
 * for every reference in the file.
 */
private fun declarationNamedBy(leaf: PsiElement?): KtNamedDeclaration? {
	leaf ?: return null
	val declaration = PsiTreeUtil.getParentOfType(leaf, KtNamedDeclaration::class.java) ?: return null
	return declaration.takeIf { it.nameIdentifier === leaf }
}
