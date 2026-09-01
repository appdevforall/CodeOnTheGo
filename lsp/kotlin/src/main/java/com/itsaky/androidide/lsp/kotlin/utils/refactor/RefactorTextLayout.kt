package com.itsaky.androidide.lsp.kotlin.utils.refactor

// Indent and line-offset helpers for emitted edits, here for the same reason: the sheet UI formats
// its previews with them, the carrier's edit builders use them, and they touch no Analysis API.

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
 * otherwise the smallest positive run of leading spaces, defaulting to a tab (the project
 * convention). Code-action edits bypass the editor's auto-indent, so emitted text must already match
 * the file's style. Mirrors the detection in `ImplementMembersAction`.
 */
fun detectIndentUnit(text: String): String {
	var minSpaces = Int.MAX_VALUE
	for (line in text.splitToSequence('\n')) {
		if (line.isEmpty()) continue
		if (line[0] == '\t') return "\t"
		if (line[0] != ' ') continue
		val spaces = line.takeWhile { it == ' ' }.length
		if (spaces in 1 until minSpaces) minSpaces = spaces
	}
	return if (minSpaces == Int.MAX_VALUE) "\t" else " ".repeat(minSpaces)
}
