package com.itsaky.androidide.lsp.java.debug.utils

private const val INLINE_MARKER_PREFIX = "\$i\$"
private const val INLINE_RECEIVER_PREFIX = "\$this\$"

private val SUSPEND_MACHINE_LOCALS =
	setOf(
		"\$continuation",
		"\$result",
		"\$completion",
	)

fun isSyntheticKotlinLocal(name: String): Boolean = name.startsWith(INLINE_MARKER_PREFIX) || name in SUSPEND_MACHINE_LOCALS

fun isInlinedRegion(visibleLocalNames: Collection<String>): Boolean =
	visibleLocalNames.any { name -> name.startsWith(INLINE_MARKER_PREFIX) }

fun kotlinLocalDisplayName(name: String): String {
	if (!name.startsWith(INLINE_RECEIVER_PREFIX)) {
		return name
	}

	val label = name.removePrefix(INLINE_RECEIVER_PREFIX)
	return if (label.isEmpty()) name else "this@$label"
}
