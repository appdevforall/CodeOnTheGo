package com.itsaky.androidide.lsp.java.debug.utils

private const val INLINE_MARKER_PREFIX = "\$i\$"
private const val INLINE_RECEIVER_PREFIX = "\$this\$"

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
 * This answers what to *display*. It does not answer whether execution is inside library code: an
 * inlined lambda the user wrote carries `$i$a$` in the user's own class, so the marker cannot tell
 * the two apart.
 */
fun isSyntheticKotlinLocal(name: String): Boolean = name.startsWith(INLINE_MARKER_PREFIX) || name in SUSPEND_MACHINE_LOCALS

/**
 * The name to show for [name], mapping an inline lambda receiver to the Kotlin syntax for it, so
 * `$this$run` reads as `this@run`. Any other name is returned unchanged.
 */
fun kotlinLocalDisplayName(name: String): String {
	if (!name.startsWith(INLINE_RECEIVER_PREFIX)) {
		return name
	}

	val label = name.removePrefix(INLINE_RECEIVER_PREFIX)
	return if (label.isEmpty()) name else "this@$label"
}
