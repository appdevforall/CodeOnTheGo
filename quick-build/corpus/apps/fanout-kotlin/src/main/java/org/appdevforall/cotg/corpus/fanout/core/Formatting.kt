package org.appdevforall.cotg.corpus.fanout.core

/** `inline` — every caller's bytecode carries a copy of this body (no shared call target). */
inline fun fmt(
	label: String,
	value: Int,
): String = "$label: $value"
