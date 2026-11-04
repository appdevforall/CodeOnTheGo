package com.itsaky.androidide.search.replace

import com.itsaky.androidide.models.SearchResult
import io.github.rosemoe.sora.text.Content

object TextReplacement {
	sealed interface Result {
		data class Applied(
			val newText: String,
		) : Result

		data object Stale : Result
	}

	fun apply(
		text: String,
		matches: List<SearchResult>,
		replacement: String,
	): Result {
		val content = Content(text)
		val spans =
			matches.map { match ->
				val start = offsetOf(content, match.start.line, match.start.column) ?: return Result.Stale
				val end = offsetOf(content, match.end.line, match.end.column) ?: return Result.Stale
				if (end < start || text.substring(start, end) != match.match) return Result.Stale
				start to end
			}
		val builder = StringBuilder(text)
		spans.sortedByDescending { it.first }.forEach { (start, end) -> builder.replace(start, end, replacement) }
		return Result.Applied(builder.toString())
	}

	private fun offsetOf(
		content: Content,
		line: Int,
		column: Int,
	): Int? {
		if (line < 0 || line >= content.lineCount || column < 0 || column > content.getColumnCount(line)) return null
		return content.getCharIndex(line, column)
	}
}
