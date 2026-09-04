package com.itsaky.androidide.lsp.refactor

/** A half-open offset range `[start, end)` into the analysed file's text. */
data class TextSpan(
	val start: Int,
	val end: Int,
) {
	init {
		require(start <= end) { "start=$start > end=$end" }
	}

	val length: Int get() = end - start

	fun overlaps(other: TextSpan): Boolean = start < other.end && other.start < end
}

/** How many candidate expressions are ever offered. Keeps the chooser scannable on a phone. */
const val MAX_CANDIDATES = 3

/** Used when neither the expression's shape nor its type suggests anything better. */
const val FALLBACK_NAME = "value"
