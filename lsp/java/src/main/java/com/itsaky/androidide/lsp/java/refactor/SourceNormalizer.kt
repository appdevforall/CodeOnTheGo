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
			// The comment's own trailing newline goes with it; leaving it for the whitespace branch would
			// put back the space the operator before the comment already swallowed.
			while (i < text.length && text[i].isWhitespace()) i++
			pendingSpace = out.spaceSurvivesComment()
			continue
		}

		if (c == '/' && i + 1 < text.length && text[i + 1] == '*') {
			i += 2
			while (i + 1 < text.length && !(text[i] == '*' && text[i + 1] == '/')) i++
			i = (i + 2).coerceAtMost(text.length)
			while (i < text.length && text[i].isWhitespace()) i++
			pendingSpace = out.spaceSurvivesComment()
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
		 * Whitespace around an operator or separator carries no meaning in Java, so `a+1` and `a + 1` must
		 * normalize alike -- otherwise the occurrence search silently skips the differently-spelled site
		 * and the user is never told a match was missed.
		 *
		 * The exception is two characters that could lex into one token: dropping the space in `a - -b`
		 * would produce `a--b`, which is a different expression. Only combinable characters need that
		 * guard, so `items.size() + 1` still closes up to `items.size()+1`.
		 */
		val previous = out.lastOrNull()
		// Two combinable characters must stay apart: closing up `a - -b` would produce `a--b`, a different
		// expression. Anything else loses nothing, so the space goes.
		val clashesBehind = previous != null && previous in COMBINABLE && c in COMBINABLE
		if (c in PUNCTUATION && !clashesBehind) pendingSpace = false
		if (pendingSpace) {
			out.append(' ')
			pendingSpace = false
		}
		out.append(c)
		i++
		if (c in PUNCTUATION) {
			var next = i
			while (next < text.length && text[next].isWhitespace()) next++
			// A comment's `/` is not an operator: the comment is about to vanish, so it cannot combine.
			val startsComment =
				next + 1 < text.length && text[next] == '/' && (text[next + 1] == '/' || text[next + 1] == '*')
			val clashesAhead =
				c in COMBINABLE && next < text.length && text[next] in COMBINABLE && !startsComment
			// Dropping it here as well as behind is what makes `a + 1` and `a+1` the same string; doing only
			// one side left `a+ 1`, which still failed to match.
			if (!clashesAhead) i = next
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
	// A text block's delimiter is three quotes. Stopping at the first would leave its body outside any
	// literal, so its significant whitespace would be collapsed and two different blocks could compare
	// equal -- which would let the occurrence search replace a site that does not hold the same value.
	if (quote == '"' && text.startsWith(TEXT_BLOCK, start)) {
		val close = text.indexOf(TEXT_BLOCK, start + TEXT_BLOCK.length)
		val end = if (close < 0) text.length else close + TEXT_BLOCK.length
		out.append(text, start, end)
		return end
	}

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

private const val TEXT_BLOCK = "\"\"\""

/** Everything that is neither an identifier character nor a literal delimiter. */
private val PUNCTUATION = "+-*/%=!<>&|^~:.?,;(){}[]".toSet()

/**
 * The punctuation that can lex into a longer token when juxtaposed, so a space between two of them is
 * load-bearing: `a - -b` must not collapse into `a--b`.
 */
private val COMBINABLE = "+-*/%=!<>&|^~:.".toSet()

/**
 * Whether a space is still needed where a comment was.
 *
 * A comment sitting after an operator must not put back the space that operator just swallowed:
 * `a + // why` then `b` has to reach `a+b`, the same as `a+b`.
 */
private fun StringBuilder.spaceSurvivesComment(): Boolean = isNotEmpty() && last() !in PUNCTUATION
