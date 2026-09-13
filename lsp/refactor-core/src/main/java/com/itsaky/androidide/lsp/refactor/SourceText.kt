package com.itsaky.androidide.lsp.refactor

// The offset arithmetic every extract refactoring needs, with no notion of a language in it. These were
// duplicated per language server until ADFA-5047; none of them touches a javac `Tree` or a
// `KtExpression`, and each fix used to have to land twice -- `detectIndentUnit` had already drifted.

/** Offset of the start of the line containing [offset]. */
fun lineStartOffset(
	text: String,
	offset: Int,
): Int = text.lastIndexOf('\n', (offset - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }

/** The run of spaces/tabs at the start of [offset]'s line. */
fun leadingIndentAt(
	text: String,
	offset: Int,
): String {
	val lineStart = lineStartOffset(text, offset)
	return text.substring(lineStart, offset.coerceAtLeast(lineStart)).takeWhile { it == ' ' || it == '\t' }
}

/**
 * One indentation level for [text], inferred from its own lines: a tab if any line is tab-indented,
 * else the smallest positive run of leading spaces, defaulting to a tab (the project convention).
 *
 * Code-action edits bypass the editor's auto-indent, so emitted text must already match the file's
 * style.
 */
fun detectIndentUnit(text: String): String {
	var minSpaces = Int.MAX_VALUE
	for (line in text.splitToSequence('\n')) {
		if (line.isEmpty()) continue
		if (line[0] == '\t') return "\t"
		if (line[0] != ' ') continue
		val trimmed = line.trimStart()
		// A block-comment continuation (` * text`, ` */`) is alignment, not indentation, and its single
		// leading space would otherwise win the minimum on virtually every real file.
		if (trimmed.startsWith('*')) continue
		val spaces = line.length - trimmed.length
		// A one-space indent unit is not a real style, so it can only be a line this scan misread.
		if (spaces in 2 until minSpaces) minSpaces = spaces
	}
	return if (minSpaces == Int.MAX_VALUE) "\t" else " ".repeat(minSpaces)
}

/** CRLF only when the file already uses it, so the edit does not mix line endings. */
fun detectNewline(text: String): String = if (text.contains("\r\n")) "\r\n" else "\n"

/** The offset where the run of whitespace ending at [offset] begins. */
fun startOfWhitespaceBefore(
	text: String,
	offset: Int,
): Int {
	var index = offset.coerceIn(0, text.length)
	while (index > 0 && text[index - 1].isWhitespace()) index--
	return index
}

/** The offset where the run of whitespace starting at [offset] ends. */
fun endOfWhitespaceAfter(
	text: String,
	offset: Int,
): Int {
	var index = offset.coerceIn(0, text.length)
	while (index < text.length && text[index].isWhitespace()) index++
	return index
}

/**
 * Substitutes [name] for every one of [targets] inside [span], right-to-left so an earlier replacement
 * cannot invalidate a later offset. Targets outside [span] are ignored.
 */
fun replaceOccurrences(
	fileText: String,
	span: TextSpan,
	targets: List<TextSpan>,
	name: String,
): String {
	val builder = StringBuilder(fileText.substring(span.start, span.end))
	targets
		.filter { it.start >= span.start && it.end <= span.end }
		.sortedByDescending { it.start }
		.forEach { builder.replace(it.start - span.start, it.end - span.start, name) }
	return builder.toString()
}
