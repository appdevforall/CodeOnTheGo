package org.appdevforall.cotg.corpus.medium.formatters

class TextTruncator {
	fun truncate(
		text: String,
		maxLength: Int,
	): String = if (text.length <= maxLength) text else text.take(maxLength - 1) + "…"
}
