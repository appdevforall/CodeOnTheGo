package com.itsaky.androidide.utils

/** Characters a file name on shared storage cannot hold. */
internal val UNSAFE_FILENAME_CHARS = Regex("[\\\\/:*?\"<>|]")

/**
 * [raw] with every character a file name cannot hold replaced by `_`, and trimmed.
 *
 * @param fallback the name to use when nothing is left.
 */
internal fun safeFileName(
	raw: String,
	fallback: String,
): String = raw.replace(UNSAFE_FILENAME_CHARS, "_").trim().ifBlank { fallback }
