package com.itsaky.androidide.lsp.java.debug.utils

private const val INLINE_MARKER_PREFIX = "\$i\$"
private const val INLINE_COPY_SUFFIX = "\$iv"

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
 * A marker says what to hide, not whose code is running: `$i$a$` scopes a lambda the user wrote,
 * inlined into the user's own class, exactly as `$i$f$` scopes a library body inlined there.
 *
 * Every local copied out of an inlined body carries one `$iv` per inlining, so the suffixes come off
 * before the prefix and set tests -- `$i$f$mapTo$iv$iv` is a marker just as `$i$f$mapTo` is.
 *
 * The suffix stays on the names that survive, and is deliberately not stripped for display.
 * `item$iv$iv` and `destination$iv$iv` are `mapTo`'s loop variable and accumulator, not the user's,
 * and the suffix is the only cue marking them so: shown as `item` and `destination` they sit beside
 * a user's own `item` with nothing to tell them apart. The cost is that a user's own inline-function
 * local reads `started$iv`. Separating the two needs the Kotlin stratum.
 */
fun isSyntheticKotlinLocal(name: String): Boolean {
	val base = withoutInlineCopySuffixes(name)
	return base.startsWith(INLINE_MARKER_PREFIX) || base in SUSPEND_MACHINE_LOCALS
}

/** [name] with every `$iv` the inliner appended removed, one per inlining. */
private fun withoutInlineCopySuffixes(name: String): String {
	var base = name
	while (base.length > INLINE_COPY_SUFFIX.length && base.endsWith(INLINE_COPY_SUFFIX)) {
		base = base.dropLast(INLINE_COPY_SUFFIX.length)
	}
	return base
}
