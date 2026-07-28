package com.itsaky.androidide.lsp.kotlin.navigation

import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.tree.IElementType
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtArrayAccessExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtOperationReferenceExpression
import org.jetbrains.kotlin.psi.KtPropertyDelegate
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("ReferenceAtCaret")

/**
 * Tokens a caret may sit on and still name something. An accept-list rather than a reject-list, so
 * whitespace, comments, string bodies, braces, literals and every other keyword navigate nowhere.
 * IDENTIFIER also covers KDoc link names, which are lexed as identifiers inside a KDocName.
 */
private val NAVIGABLE_TOKENS: Set<IElementType> =
	setOf(
		KtTokens.IDENTIFIER,
		KtTokens.THIS_KEYWORD,
		KtTokens.SUPER_KEYWORD,
		KtTokens.IN_KEYWORD, // for-loop iterator/hasNext/next
		KtTokens.BY_KEYWORD, // property delegate getValue/setValue
		KtTokens.LPAR, // invoke
		KtTokens.LBRACKET, // get/set
	)

/**
 * Levels walked from the caret's leaf token up to the element that resolves. Two is what the
 * reference kinds actually need: a name's reference sits on its parent, and a convention host at
 * most one above that (the call-paren case needs both levels). The cap just bounds the walk so it
 * cannot wander arbitrarily far into an unrelated enclosing element; it is not what stops a caret on
 * a declaration's own name from resolving to an enclosing call - in `run { fun inner() {} }`, a
 * caret on `inner` finds nothing because none of the intervening ancestors are resolvable, several
 * levels before the cap would even matter.
 */
private const val MAX_CLIMB = 2

/**
 * The element to resolve for a caret at [offset] in [file], or null when the caret names nothing.
 *
 * Callers must hold the project read lock. Pure PSI: no analysis session is needed or used.
 */
internal fun referenceAtCaret(
	file: KtFile,
	offset: Int,
): KtElement? {
	// Touch caret placement is imprecise, so a caret resting just past an identifier must behave
	// like a caret inside it.
	val leaf =
		navigableLeafAt(file, offset)
			?: navigableLeafAt(file, (offset - 1).coerceAtLeast(0))
			?: run {
				logger.debug("No navigable token at offset {} in {}", offset, file.name)
				return null
			}

	var node: PsiElement? = leaf.parent
	var climbed = 0
	while (node != null && node !is KtFile && climbed < MAX_CLIMB) {
		if (node is KtElement && node.isResolvable()) {
			return node
		}
		node = node.parent
		climbed++
	}

	logger.debug("Token '{}' at offset {} in {} resolves nothing", leaf.text, offset, file.name)
	return null
}

private fun navigableLeafAt(
	file: KtFile,
	offset: Int,
): PsiElement? {
	val leaf = file.findElementAt(offset) ?: return null
	val type = leaf.node?.elementType
	if (type != null && type in NAVIGABLE_TOKENS) {
		return leaf
	}
	// Operator tokens are too many to enumerate, but the reference element wrapping them is not.
	if (leaf.parent is KtOperationReferenceExpression) {
		return leaf
	}
	return null
}

/**
 * Either the element carries a name reference, or it is a convention host whose declaration is
 * found through a resolved-call lookup instead.
 */
private fun KtElement.isResolvable(): Boolean =
	mainReference != null ||
		this is KtCallExpression ||
		this is KtArrayAccessExpression ||
		this is KtPropertyDelegate ||
		this is KtForExpression
