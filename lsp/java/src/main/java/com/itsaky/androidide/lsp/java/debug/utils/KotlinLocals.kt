package com.itsaky.androidide.lsp.java.debug.utils

private const val INLINE_MARKER_PREFIX = "\$i\$"
private const val INLINE_RECEIVER_PREFIX = "\$this\$"
private const val INLINE_COPY_SUFFIX = "\$iv"
private const val MANGLED_DOLLAR = "_u24"

private val SUSPEND_MACHINE_LOCALS =
	setOf(
		"\$continuation",
		"\$result",
		"\$completion",
	)

/**
 * Whether [name] is a local the Kotlin compiler generated rather than one the user wrote.
 *
 * Covers the inline markers (`$i$f$` for an inlined function body, `$i$a$` for an inlined lambda
 * argument) and the suspend state machine's own locals. These carry no meaning for someone reading
 * their own code, so the variables list hides them.
 *
 * Every local copied out of an inlined body also carries one `$iv` per inlining, so the suffixes come
 * off first. Stripping does not separate a library body's locals from the user's own inline-function
 * locals -- both arrive suffixed -- so a stdlib internal such as `destination$iv$iv` survives this
 * predicate under its unsuffixed name. Telling the two apart needs the Kotlin stratum, which is the
 * same limit that applies to `$i$`.
 */
fun isSyntheticKotlinLocal(name: String): Boolean {
	val base = withoutInlineCopySuffixes(name)
	return base.startsWith(INLINE_MARKER_PREFIX) || base in SUSPEND_MACHINE_LOCALS
}

/**
 * The name to show for [name]: the source name for a local copied out of an inlined body, and the
 * Kotlin syntax for an inline receiver where one can be recovered.
 *
 * `$this$map$iv` reads as `this@map`. A lambda receiver is emitted named after the enclosing method's
 * synthetic lambda instead -- `$this$direct_u24lambda_u240`, where `_u24` is a mangled `$` -- and no
 * label the user could have written is recoverable from it, so that name is left alone rather than
 * turned into a `this@` that appears nowhere in their source.
 */
fun kotlinLocalDisplayName(name: String): String {
	val base = withoutInlineCopySuffixes(name)
	if (!base.startsWith(INLINE_RECEIVER_PREFIX)) {
		return base
	}

	val label = base.removePrefix(INLINE_RECEIVER_PREFIX)
	return if (isSourceLabel(label)) "this@$label" else base
}

/** [name] with every `$iv` the inliner appended removed, one per inlining. */
private fun withoutInlineCopySuffixes(name: String): String {
	var base = name
	while (base.length > INLINE_COPY_SUFFIX.length && base.endsWith(INLINE_COPY_SUFFIX)) {
		base = base.dropLast(INLINE_COPY_SUFFIX.length)
	}
	return base
}

/** Whether [label] is a name the user could have written, rather than a synthetic lambda's. */
private fun isSourceLabel(label: String): Boolean =
	label.isNotEmpty() &&
		!label.contains(MANGLED_DOLLAR) &&
		label.all { it.isLetterOrDigit() || it == '_' }
