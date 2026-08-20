package com.itsaky.androidide.lsp.java.refactor

/**
 * Strips comments and collapses whitespace outside string and character literals.
 *
 * Deliberately not a kind-by-kind structural comparator: javac's `Tree` exposes no generic child list, so
 * a structural walk means one visitor case per tree kind, and a kind left unhandled by accident silently
 * answers "not equal". Java has no string interpolation, so a literal is opaque and needs no recursion.
 */
internal fun normalizeSource(text: String): String {
	val out = StringBuilder(text.length)
	var i = 0
	var pendingSpace = false

	while (i < text.length) {
		val c = text[i]

		if (c == '/' && i + 1 < text.length && text[i + 1] == '/') {
			while (i < text.length && text[i] != '\n') i++
			pendingSpace = out.isNotEmpty()
			continue
		}

		if (c == '/' && i + 1 < text.length && text[i + 1] == '*') {
			i += 2
			while (i + 1 < text.length && !(text[i] == '*' && text[i + 1] == '/')) i++
			i = (i + 2).coerceAtMost(text.length)
			pendingSpace = out.isNotEmpty()
			continue
		}

		if (c == '"' || c == '\'') {
			if (pendingSpace) {
				out.append(' ')
				pendingSpace = false
			}
			i = appendLiteral(text, i, c, out)
			continue
		}

		if (c.isWhitespace()) {
			pendingSpace = out.isNotEmpty()
			i++
			continue
		}

		/*
		 * Whitespace next to a member-select dot is never significant in Java, and a wrapped call chain
		 * (`items\n\t.stream()`) is the most common multi-line expression there is. Dropping it is what
		 * lets a wrapped occurrence match the same expression written on one line; without it the
		 * occurrence search silently misses every wrapped repeat.
		 */
		if (c == '.') pendingSpace = false
		if (pendingSpace) {
			out.append(' ')
			pendingSpace = false
		}
		out.append(c)
		i++
		if (c == '.') {
			while (i < text.length && text[i].isWhitespace()) i++
		}
	}
	return out.toString()
}

/**
 * A backslash escapes whatever follows, so `"a\""` does not end at the middle quote and `"a\\"` does end
 * at the last. An unterminated literal, possible mid-edit, consumes to the end rather than looping.
 */
private fun appendLiteral(
	text: String,
	start: Int,
	quote: Char,
	out: StringBuilder,
): Int {
	out.append(quote)
	var i = start + 1
	while (i < text.length) {
		val c = text[i]
		out.append(c)
		i++
		if (c == '\\') {
			if (i < text.length) {
				out.append(text[i])
				i++
			}
			continue
		}
		if (c == quote) return i
	}
	return i
}
