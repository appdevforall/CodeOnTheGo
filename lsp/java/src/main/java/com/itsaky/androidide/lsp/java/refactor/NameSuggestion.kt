package com.itsaky.androidide.lsp.java.refactor

import com.itsaky.androidide.lsp.ui.isIdentifier
import openjdk.source.tree.ArrayAccessTree
import openjdk.source.tree.IdentifierTree
import openjdk.source.tree.MemberSelectTree
import openjdk.source.tree.MethodInvocationTree
import openjdk.source.tree.NewClassTree
import openjdk.source.tree.ParameterizedTypeTree
import openjdk.source.tree.ParenthesizedTree
import openjdk.source.tree.Tree
import openjdk.source.tree.TypeCastTree

/**
 * The restricted identifiers -- `var`, `yield`, `record`, `sealed`, `permits` -- are legal variable names
 * and are deliberately absent. `true`, `false` and `null` are literals, so they are present.
 */
val JAVA_KEYWORDS =
	setOf(
		"abstract",
		"assert",
		"boolean",
		"break",
		"byte",
		"case",
		"catch",
		"char",
		"class",
		"const",
		"continue",
		"default",
		"do",
		"double",
		"else",
		"enum",
		"extends",
		"final",
		"finally",
		"float",
		"for",
		"goto",
		"if",
		"implements",
		"import",
		"instanceof",
		"int",
		"interface",
		"long",
		"native",
		"new",
		"package",
		"private",
		"protected",
		"public",
		"return",
		"short",
		"static",
		"strictfp",
		"super",
		"switch",
		"synchronized",
		"this",
		"throw",
		"throws",
		"transient",
		"try",
		"void",
		"volatile",
		"while",
		"true",
		"false",
		"null",
	)

/**
 * Shape (`items.size()` -> `size`), then rendered type (`List<String>` -> `list`), then [FALLBACK_NAME],
 * uniquified against [takenNames]. Shape beats type because `size` and `count` are far better names than
 * `int` and `string`. A primitive type yields its own keyword, which the sanitise check turns into
 * [FALLBACK_NAME] rather than emitting `int int = ...`.
 */
fun suggestVariableName(
	expression: Tree,
	typeName: String?,
	takenNames: Set<String>,
): String {
	val base =
		nameFromShape(expression)
			?: typeName?.let(::nameFromType)
			?: FALLBACK_NAME
	val sanitised = base.takeIf { isIdentifier(it) && it !in JAVA_KEYWORDS } ?: FALLBACK_NAME
	return uniqueName(sanitised, takenNames)
}

internal fun nameFromShape(tree: Tree): String? =
	when (tree) {
		is ParenthesizedTree -> nameFromShape(tree.expression)

		is TypeCastTree -> nameFromShape(tree.expression)

		is MethodInvocationTree -> nameFromShape(tree.methodSelect)

		// A constructor call is named after a *type*, so it needs decapitalising where an identifier
		// reached as a value (`items` -> `items`) must not be touched.
		is NewClassTree -> nameFromShape(tree.identifier)?.decapitaliseFirst()

		is ParameterizedTypeTree -> nameFromShape(tree.type)

		is MemberSelectTree -> stripAccessorPrefix(tree.identifier.toString())

		is IdentifierTree -> stripAccessorPrefix(tree.name.toString())

		is ArrayAccessTree -> nameFromShape(tree.expression)

		else -> null
	}?.takeIf { it.isNotBlank() }

/** `getFoo` -> `foo`, `isReady` -> `ready`. Leaves anything else alone. */
internal fun stripAccessorPrefix(name: String): String {
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

/** `List<Foo>` -> `list`, `java.time.Duration` -> `duration`, `String[]` -> `string`. */
internal fun nameFromType(typeName: String): String? =
	typeName
		.substringBefore('<')
		.removeSuffix("[]")
		.substringAfterLast('.')
		.trimEnd('[', ']')
		.takeIf { it.isNotBlank() }
		?.decapitaliseFirst()

internal fun String.decapitaliseFirst(): String = if (isEmpty()) this else this[0].lowercaseChar() + substring(1)

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
