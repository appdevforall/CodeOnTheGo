package com.itsaky.androidide.utils

object WordBoundary {
	@JvmStatic
	fun isWholeWord(
		text: CharSequence,
		start: Int,
		end: Int,
	): Boolean {
		if (start >= end) return false
		if (isWord(text[start]) && start > 0 && isWord(text[start - 1])) return false
		if (isWord(text[end - 1]) && end < text.length && isWord(text[end])) return false
		return true
	}

	private fun isWord(c: Char): Boolean = Character.isJavaIdentifierPart(c)
}
