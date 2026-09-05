package com.itsaky.androidide.lsp.refactor

// The language-independent half of name suggestion: what a name looks like, not what the language
// allows. Picking a name from an expression's *shape* stays per-language, since only a language server
// knows what a call or a member access is.

/** `getFoo` -> `foo`, `isReady` -> `ready`. Leaves anything else alone. */
fun stripAccessorPrefix(name: String): String {
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

/**
 * `List<Foo>` -> `list`, `java.time.Duration` -> `duration`, `String[]` -> `string`, `Foo?` -> `foo`.
 *
 * Both languages' spellings are handled in one pass, which is what let their two copies of this differ
 * unnoticed: javac renders an array as `String[]` and never appends a nullability marker, Kotlin renders
 * `Foo?` / `Foo!` and never uses brackets, so stripping all of them is a no-op for whichever language
 * did not produce them.
 */
fun nameFromType(typeName: String): String? =
	typeName
		.substringBefore('<')
		.removeSuffix("[]")
		.substringAfterLast('.')
		.trimEnd('[', ']', '?', '!')
		.takeIf { it.isNotBlank() }
		?.decapitaliseFirst()

fun String.decapitaliseFirst(): String = if (isEmpty()) this else this[0].lowercaseChar() + substring(1)

/** `size` -> `size1` -> `size2` until nothing in [takenNames] matches. */
fun uniqueName(
	base: String,
	takenNames: Set<String>,
): String {
	if (base !in takenNames) return base
	var suffix = 1
	while ("$base$suffix" in takenNames) suffix++
	return "$base$suffix"
}
