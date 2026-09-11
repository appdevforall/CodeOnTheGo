package com.itsaky.androidide.lsp.kotlin.utils.refactor

import com.itsaky.androidide.lsp.refactor.FALLBACK_NAME
import com.itsaky.androidide.lsp.refactor.nameFromType
import com.itsaky.androidide.lsp.refactor.stripAccessorPrefix
import com.itsaky.androidide.lsp.refactor.uniqueName
import com.itsaky.androidide.lsp.ui.isIdentifier
import org.jetbrains.kotlin.psi.KtArrayAccessExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

/**
 * Kotlin's hard keywords -- the ones that are never valid identifiers. Soft and modifier keywords
 * (`by`, `data`, `it`, ...) are legal names and are deliberately absent.
 */
internal val HARD_KEYWORDS =
	setOf(
		"as",
		"break",
		"class",
		"continue",
		"do",
		"else",
		"false",
		"for",
		"fun",
		"if",
		"in",
		"interface",
		"is",
		"null",
		"object",
		"package",
		"return",
		"super",
		"this",
		"throw",
		"true",
		"try",
		"typealias",
		"typeof",
		"val",
		"var",
		"when",
		"while",
	)

/**
 * Suggests a name for the value [expression] produces.
 *
 * Tried in order:
 * 1. **The expression's shape** -- `items.size` -> `size`, `a.b.c()` -> `c`, `getFoo()` -> `foo`,
 *    `foo(x)` -> `foo`, an interpolated string -> `text`, `xs[i]` -> `xs` element naming.
 * 2. **The resolved type**, lowercased -- `List<Foo>` -> `list`, `Duration` -> `duration`. Pass null
 *    when the type is unavailable.
 * 3. [FALLBACK_NAME].
 *
 * The result is then made unique against [takenNames] by appending `1`, `2`, ... Shape beats type
 * because `size`, `count` and `name` are far better names than `int` and `string`, and type-derived
 * names collide constantly.
 */
fun suggestVariableName(
	expression: KtExpression,
	typeName: String?,
	takenNames: Set<String>,
): String {
	val base =
		nameFromShape(expression)
			?: typeName?.let(::nameFromType)
			?: FALLBACK_NAME
	val sanitised = base.takeIf { isIdentifier(it) && it !in HARD_KEYWORDS } ?: FALLBACK_NAME
	return uniqueName(sanitised, takenNames)
}

private fun nameFromShape(expression: KtExpression): String? =
	when (expression) {
		is KtParenthesizedExpression -> expression.expression?.let(::nameFromShape)
		is KtQualifiedExpression -> expression.selectorExpression?.let(::nameFromShape)
		is KtCallExpression -> (expression.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()?.let(::stripAccessorPrefix)
		is KtNameReferenceExpression -> expression.getReferencedName().let(::stripAccessorPrefix)
		is KtStringTemplateExpression -> "text"
		is KtArrayAccessExpression -> expression.arrayExpression?.let(::nameFromShape)
		else -> null
	}?.takeIf { it.isNotBlank() }
