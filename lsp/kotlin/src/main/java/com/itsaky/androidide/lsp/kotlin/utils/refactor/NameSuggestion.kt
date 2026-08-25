package com.itsaky.androidide.lsp.kotlin.utils.refactor

import org.jetbrains.kotlin.psi.KtArrayAccessExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

/** Used when neither the expression's shape nor its type suggests anything better. */
const val FALLBACK_NAME = "value"

/**
 * Kotlin's hard keywords -- the ones that are never valid identifiers. Soft and modifier keywords
 * (`by`, `data`, `it`, ...) are legal names and are deliberately absent.
 */
private val HARD_KEYWORDS =
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

/** Why a proposed name cannot be used. Null-free alternative to throwing for user input. */
enum class NameProblem {
	Blank,
	NotAnIdentifier,
	Keyword,
	AlreadyTaken,
}

/**
 * Validates a user-supplied name against Kotlin's identifier rules and the names already visible at
 * the anchor point. Returns null when the name is usable.
 *
 * Backtick-quoted names are rejected rather than supported: they are legal Kotlin but a poor
 * suggestion for a generated local, and accepting them would mean validating the quoted form too.
 */
fun validateVariableName(
	name: String,
	takenNames: Set<String>,
): NameProblem? {
	if (name.isBlank()) return NameProblem.Blank
	if (!isIdentifier(name)) return NameProblem.NotAnIdentifier
	if (name in HARD_KEYWORDS) return NameProblem.Keyword
	if (name in takenNames) return NameProblem.AlreadyTaken
	return null
}

private fun isIdentifier(name: String): Boolean {
	if (name.isEmpty()) return false
	if (!(name[0].isLetter() || name[0] == '_')) return false
	return name.all { it.isLetterOrDigit() || it == '_' }
}

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

/** `getFoo` -> `foo`, `isReady` -> `ready`. Leaves anything else alone. */
private fun stripAccessorPrefix(name: String): String {
	for (prefix in ACCESSOR_PREFIXES) {
		if (name.length > prefix.length &&
			name.startsWith(prefix) &&
			name[prefix.length].isUpperCase()
		) {
			return name.substring(prefix.length).decapitaliseFirst()
		}
	}
	return name
}

private val ACCESSOR_PREFIXES = listOf("get", "is", "has")

/** `List<Foo>` -> `list`, `kotlin.time.Duration` -> `duration`, `Array<String>` -> `array`. */
private fun nameFromType(typeName: String): String? =
	typeName
		.substringBefore('<')
		.substringAfterLast('.')
		.trimEnd('?', '!')
		.takeIf { it.isNotBlank() }
		?.decapitaliseFirst()

private fun String.decapitaliseFirst(): String = if (isEmpty()) this else this[0].lowercaseChar() + substring(1)

/** `size` -> `size1` -> `size2` until nothing in [takenNames] matches. */
internal fun uniqueName(
	base: String,
	takenNames: Set<String>,
): String {
	if (base !in takenNames) return base
	var suffix = 1
	while ("$base$suffix" in takenNames) suffix++
	return "$base$suffix"
}
