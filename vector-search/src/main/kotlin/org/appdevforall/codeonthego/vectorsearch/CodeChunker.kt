/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.appdevforall.codeonthego.vectorsearch

import java.io.File

/**
 * Data class representing a chunked piece of code with metadata.
 *
 * @param content The actual text content of the chunk
 * @param startLine The starting line number (0-indexed) in the original file
 * @param endLine The ending line number (0-indexed, inclusive) in the original file
 * @param isCodeChunk Whether this chunk contains code boundaries (functions, classes, etc.)
 */
data class CodeChunk(
    val content: String,
    val startLine: Int,
    val endLine: Int,
    val isCodeChunk: Boolean,
)

/**
 * Semantic file chunker that splits files into manageable pieces based on language-specific
 * boundaries (functions, classes, etc.) with configurable overlap for context preservation.
 *
 * Supports:
 * - Language detection from file extensions
 * - Smart boundary detection for Kotlin/Java code
 * - Configurable overlap between chunks to maintain context
 * - Merging of very small chunks (< minChunkSize) with next chunk
 * - Simple text files bypass boundary detection
 */
object CodeChunker {
    // Default configuration.
    // Chunks are kept small so a match localizes to a single method/block rather than a
    // whole file, while staying within the embedding model's token budget.
    private const val DEFAULT_MAX_CHUNK_SIZE = 600
    private const val DEFAULT_OVERLAP_SIZE = 80
    private const val DEFAULT_MIN_CHUNK_SIZE = 5

    // Code file extensions that should use boundary detection
    private val CODE_EXTENSIONS = setOf(
        "kt", "java", "scala", "groovy",
        "py", "js", "ts", "tsx", "jsx",
        "go", "rs", "c", "cpp", "h", "hpp",
        "cs", "swift", "rb", "php"
    )

    // Text file extensions that should NOT use boundary detection
    private val TEXT_EXTENSIONS = setOf(
        "txt", "md", "rst", "csv", "json", "xml", "yaml", "yml",
        "html", "css", "sql"
    )

    /**
     * Chunks a file into semantic pieces.
     *
     * @param file The file to chunk
     * @param maxChunkSize Maximum size of each chunk in characters (default: 2000)
     * @param overlapSize Number of characters to overlap between chunks (default: 100)
     * @param minChunkSize Minimum chunk size before merging with next chunk (default: 5 lines)
     * @return List of CodeChunk objects representing the file
     * @throws IllegalArgumentException if file doesn't exist or cannot be read
     */
    fun chunkFile(
        file: File,
        maxChunkSize: Int = DEFAULT_MAX_CHUNK_SIZE,
        overlapSize: Int = DEFAULT_OVERLAP_SIZE,
        minChunkSize: Int = DEFAULT_MIN_CHUNK_SIZE,
    ): List<CodeChunk> {
        require(file.exists()) { "File does not exist: ${file.absolutePath}" }
        require(file.isFile) { "Path is not a file: ${file.absolutePath}" }
        require(maxChunkSize > 0) { "maxChunkSize must be positive" }
        require(overlapSize >= 0) { "overlapSize must be non-negative" }
        require(minChunkSize > 0) { "minChunkSize must be positive" }

        val content = file.readText()
        return chunkText(
            content,
            file.extension,
            maxChunkSize,
            overlapSize,
            minChunkSize,
        )
    }

    /**
     * Chunks text content into semantic pieces.
     *
     * @param content The text content to chunk
     * @param fileExtension The file extension to determine language (e.g., "kt", "java", "py")
     * @param maxChunkSize Maximum size of each chunk in characters (default: 2000)
     * @param overlapSize Number of characters to overlap between chunks (default: 100)
     * @param minChunkSize Minimum chunk size before merging with next chunk (default: 5 lines)
     * @return List of CodeChunk objects
     */
    fun chunkText(
        content: String,
        fileExtension: String = "txt",
        maxChunkSize: Int = DEFAULT_MAX_CHUNK_SIZE,
        overlapSize: Int = DEFAULT_OVERLAP_SIZE,
        minChunkSize: Int = DEFAULT_MIN_CHUNK_SIZE,
    ): List<CodeChunk> {
        if (content.isBlank()) {
            return emptyList()
        }

        // Strip leading license/comment headers (and XML prolog) so the shared boilerplate
        // doesn't pollute embeddings or snippets. The removed line count is added back to
        // each chunk's range so navigation still lands on the right line.
        val (cleaned, lineOffset) = stripLeadingBoilerplate(content, fileExtension)
        if (cleaned.isBlank()) {
            return emptyList()
        }

        val lines = cleaned.split("\n")
        val isCodeFile = isCodeLanguage(fileExtension)

        // For text files or very small files, use simple line-based chunking
        val chunks = if (!isCodeFile || lines.size <= 10) {
            simpleChunk(lines, maxChunkSize, overlapSize)
        } else {
            // For code files, use boundary-aware chunking
            semanticChunk(lines, maxChunkSize, overlapSize, minChunkSize)
        }

        return if (lineOffset == 0) {
            chunks
        } else {
            chunks.map {
                it.copy(
                    startLine = it.startLine + lineOffset,
                    endLine = it.endLine + lineOffset,
                )
            }
        }
    }

    /**
     * Strips a leading license/comment header (for code) or XML prolog/comment (for XML) from
     * [content]. Returns the cleaned content and the number of leading lines removed, so the
     * caller can offset chunk line numbers back onto the original file.
     *
     * Only contiguous *leading* boilerplate is removed, so the offset is uniform for all
     * remaining lines. If the file turns out to be nothing but boilerplate, the original
     * content is kept (offset 0) to avoid dropping it entirely.
     */
    private fun stripLeadingBoilerplate(content: String, fileExtension: String): Pair<String, Int> {
        val lines = content.split("\n")
        var idx = 0

        fun skipBlankLines() {
            while (idx < lines.size && lines[idx].isBlank()) idx++
        }

        skipBlankLines()
        val firstNonBlank = idx

        when {
            isCodeLanguage(fileExtension) -> {
                // Leading block comment (e.g. license header): /* ... */
                if (idx < lines.size && lines[idx].trimStart().startsWith("/*")) {
                    while (idx < lines.size && !lines[idx].contains("*/")) idx++
                    if (idx < lines.size) idx++ // consume the line with the closing */
                }
                skipBlankLines()
                // Leading single-line comments: //
                while (idx < lines.size && lines[idx].trimStart().startsWith("//")) idx++
                skipBlankLines()
            }

            fileExtension.lowercase() == "xml" -> {
                // XML declaration: <?xml ... ?>
                if (idx < lines.size && lines[idx].trimStart().startsWith("<?xml")) idx++
                skipBlankLines()
                // Leading comment block: <!-- ... -->
                if (idx < lines.size && lines[idx].trimStart().startsWith("<!--")) {
                    while (idx < lines.size && !lines[idx].contains("-->")) idx++
                    if (idx < lines.size) idx++
                }
                skipBlankLines()
            }
        }

        // Nothing stripped beyond initial blanks, or the whole file was boilerplate:
        // keep the original content so we never lose everything.
        if (idx <= firstNonBlank || idx >= lines.size) {
            return content to 0
        }

        val cleaned = lines.subList(idx, lines.size).joinToString("\n")
        return cleaned to idx
    }

    /**
     * Simple line-based chunking without boundary detection.
     * Used for text files or very small files.
     */
    private fun simpleChunk(
        lines: List<String>,
        maxChunkSize: Int,
        overlapSize: Int,
    ): List<CodeChunk> {
        val chunks = mutableListOf<CodeChunk>()
        var currentStart = 0

        while (currentStart < lines.size) {
            val currentChunk = mutableListOf<String>()
            var currentSize = 0
            var currentEnd = currentStart

            // Add lines until we reach maxChunkSize or run out of lines
            while (currentEnd < lines.size) {
                val line = lines[currentEnd]
                val lineSize = line.length + 1 // +1 for newline
                if (currentSize + lineSize > maxChunkSize && currentChunk.isNotEmpty()) {
                    break
                }
                currentChunk.add(line)
                currentSize += lineSize
                currentEnd++
            }

            if (currentChunk.isNotEmpty()) {
                chunks.add(
                    CodeChunk(
                        content = currentChunk.joinToString("\n"),
                        startLine = currentStart,
                        endLine = currentEnd - 1,
                        isCodeChunk = false,
                    )
                )

                // Move to next chunk with overlap
                val overlapLines = calculateOverlapLines(currentChunk, overlapSize)
                currentStart = maxOf(currentEnd - overlapLines, currentStart + 1)
            } else {
                // If even a single line is too large, include it anyway
                if (currentEnd < lines.size) {
                    chunks.add(
                        CodeChunk(
                            content = lines[currentEnd],
                            startLine = currentEnd,
                            endLine = currentEnd,
                            isCodeChunk = false,
                        )
                    )
                    currentEnd++
                    currentStart = currentEnd
                } else {
                    break
                }
            }
        }

        return chunks
    }

    /**
     * Semantic chunking for code files.
     * Breaks on function/class boundaries and respects minChunkSize.
     */
    private fun semanticChunk(
        lines: List<String>,
        maxChunkSize: Int,
        overlapSize: Int,
        minChunkSize: Int,
    ): List<CodeChunk> {
        val chunks = mutableListOf<CodeChunk>()
        var currentStart = 0

        while (currentStart < lines.size) {
            val (chunkLines, nextStart, brokeAtDeclaration) = buildSemanticChunk(
                lines,
                currentStart,
                maxChunkSize,
            )

            if (chunkLines.isEmpty()) {
                break
            }

            // Expand a too-small chunk so we don't emit slivers — but never across a declaration
            // boundary (that would re-absorb the next method into this chunk and undo the
            // per-method localization). Only meaningful for mid-flow (size-limit) breaks.
            if (chunkLines.size < minChunkSize && nextStart < lines.size && !brokeAtDeclaration) {
                val expandedLines = chunkLines.toMutableList()
                var expandedStart = nextStart

                while (expandedLines.size < minChunkSize &&
                    expandedStart < lines.size &&
                    !isDeclarationStart(lines[expandedStart])
                ) {
                    expandedLines.add(lines[expandedStart])
                    expandedStart++
                }

                chunks.add(
                    CodeChunk(
                        content = expandedLines.joinToString("\n"),
                        startLine = currentStart,
                        endLine = currentStart + expandedLines.size - 1,
                        isCodeChunk = true,
                    )
                )

                currentStart = expandedStart
            } else {
                chunks.add(
                    CodeChunk(
                        content = chunkLines.joinToString("\n"),
                        startLine = currentStart,
                        endLine = currentStart + chunkLines.size - 1,
                        isCodeChunk = true,
                    )
                )

                // The chunk reached the end of the file: stop, otherwise the overlap step below
                // would keep re-emitting shrinking fragments of the already-covered tail.
                if (nextStart >= lines.size) {
                    break
                }

                // Overlap only makes sense for a mid-flow (size-limit) break, to carry context
                // into the next chunk. A clean declaration boundary starts the next chunk exactly
                // at the declaration; adding overlap there just duplicates this chunk's tail.
                currentStart = if (brokeAtDeclaration) {
                    nextStart
                } else {
                    val overlapLines = calculateOverlapLines(chunkLines, overlapSize)
                    maxOf(nextStart - overlapLines, currentStart + 1)
                }
            }
        }

        // Post-process to handle overlap: ensure last N lines of chunk N match first N lines of N+1
        return reconcileOverlaps(chunks)
    }

    /**
     * Builds a single semantic chunk starting from a given line.
     *
     * Breaks proactively BEFORE the next top-level declaration (a new method/function/class, or
     * its leading annotation) so that a semantic match localizes to a single method instead of an
     * entire class body. Without this, a small class whose body fits inside [maxChunkSize] never
     * splits and the whole body becomes one coarse chunk. The break only fires once the chunk holds
     * substantive content that is not merely the declaration's own leading annotation(s) (see
     * [hasBreakableContentBefore]), so annotations stay attached to what they annotate. Falls back
     * to breaking AFTER a closing brace when the size limit is hit before any declaration boundary.
     *
     * @return Triple of (chunk lines, next start line index, whether the break was at a declaration
     *   boundary — a clean boundary the caller should not apply overlap across)
     */
    private fun buildSemanticChunk(
        lines: List<String>,
        startLine: Int,
        maxChunkSize: Int,
    ): Triple<List<String>, Int, Boolean> {
        val chunk = mutableListOf<String>()
        var currentSize = 0
        var currentLine = startLine
        var lastBoundaryLine = startLine // Last line with a closing boundary

        while (currentLine < lines.size) {
            val line = lines[currentLine]
            val lineSize = line.length + 1 // +1 for newline

            // Start a fresh chunk at the next declaration so each method/function is its own chunk.
            if (currentLine > startLine &&
                isDeclarationStart(line) &&
                hasBreakableContentBefore(lines, startLine, currentLine)
            ) {
                return Triple(lines.subList(startLine, currentLine), currentLine, true)
            }

            if (currentSize + lineSize > maxChunkSize && chunk.isNotEmpty()) {
                // We've exceeded the size limit
                // Break at the last boundary if we have one
                if (lastBoundaryLine >= startLine && lastBoundaryLine < currentLine) {
                    return Triple(
                        lines.subList(startLine, lastBoundaryLine + 1),
                        lastBoundaryLine + 1,
                        false,
                    )
                } else {
                    // No boundary found, just break here
                    return Triple(
                        lines.subList(startLine, currentLine),
                        currentLine,
                        false,
                    )
                }
            }

            chunk.add(line)
            currentSize += lineSize

            // Track closing braces as potential break points (prefer breaking AFTER })
            if (line.trim().startsWith("}")) {
                lastBoundaryLine = currentLine
            }

            // Also track function/class/object starts as potential context boundaries
            if (isBoundaryLine(line)) {
                lastBoundaryLine = currentLine
            }

            currentLine++
        }

        return Triple(
            lines.subList(startLine, minOf(currentLine, lines.size)),
            minOf(currentLine, lines.size),
            false,
        )
    }

    /**
     * True if lines `[startLine, currentLine)` contain substantive content beyond the trailing run
     * of blank and annotation lines that belong to the declaration at [currentLine]. Used so that
     * we break BEFORE a declaration's leading annotations (keeping them attached) but do not break
     * when the chunk so far is only those annotations.
     */
    private fun hasBreakableContentBefore(
        lines: List<String>,
        startLine: Int,
        currentLine: Int,
    ): Boolean {
        var i = currentLine - 1
        while (i >= startLine && lines[i].isBlank()) i--
        while (i >= startLine && lines[i].trim().startsWith("@")) i--
        while (i >= startLine && lines[i].isBlank()) i--
        return i >= startLine
    }

    /**
     * Checks if a line contains a code boundary marker (fun, class, object, interface).
     * Only matches meaningful declarations, not comments.
     */
    private fun isBoundaryLine(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
            return false
        }

        // Match Kotlin/Java keywords for declarations
        return trimmed.matches(KEYWORD_DECLARATION)
    }

    // Kotlin/Java keyword-led declarations (class/fun/object/...).
    private val KEYWORD_DECLARATION =
        Regex("""^(public|private|protected|internal)?\s*(fun|class|object|interface|enum|sealed|data|companion|open|abstract)\b.*""")

    // Java-style method signature: optional modifiers, then `<returnType> <name>(`. Two
    // whitespace-separated tokens before the paren is what distinguishes a declaration
    // (`void onCreate(`) from a bare call (`setContentView(`), which has only one.
    private val JAVA_METHOD_SIGNATURE =
        Regex(
            """^(?:(?:public|private|protected|static|final|abstract|synchronized|native|default|strictfp)\s+)*""" +
                """[A-Za-z_][\w.$]*(?:<[^>]*>)?(?:\[\])*\s+""" + // return type (optional generics / array)
                """[A-Za-z_]\w*\s*\(.*""", // method name followed by (
        )

    // Statements that also look like `name(...)` but are NOT declarations.
    private val STATEMENT_KEYWORDS =
        setOf("if", "for", "while", "switch", "catch", "synchronized", "else", "do", "try", "when", "return", "throw", "new", "assert", "yield", "case")

    /**
     * True if [line] begins a new top-level declaration that should start a fresh chunk: a leading
     * annotation (so it stays attached to the declaration it precedes), a Kotlin/Java keyword
     * declaration (class/fun/...), or a Java-style method signature. Used to localize a semantic
     * match to a single method/function rather than an entire class body.
     */
    private fun isDeclarationStart(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isEmpty() ||
            trimmed.startsWith("//") ||
            trimmed.startsWith("*") ||
            trimmed.startsWith("/*")
        ) {
            return false
        }

        // Annotations (@Override, @Composable, ...) precede a declaration.
        if (trimmed.startsWith("@") && trimmed.getOrNull(1)?.isLetter() == true) {
            return true
        }

        if (trimmed.matches(KEYWORD_DECLARATION)) {
            return true
        }

        // Java method signature, excluding control-flow/statements that share the `name(...)` shape.
        val firstToken = trimmed.takeWhile { it.isLetterOrDigit() || it == '_' }
        if (firstToken in STATEMENT_KEYWORDS) {
            return false
        }
        return trimmed.matches(JAVA_METHOD_SIGNATURE)
    }

    /**
     * Calculates how many lines to use for overlap based on character count.
     */
    private fun calculateOverlapLines(lines: List<String>, overlapSize: Int): Int {
        var charCount = 0
        for ((index, line) in lines.withIndex()) {
            charCount += line.length + 1 // +1 for newline
            if (charCount >= overlapSize) {
                return index + 1
            }
        }
        return minOf(lines.size / 4, 10) // Default: ~25% of chunk or max 10 lines
    }

    /**
     * Post-processes chunks to ensure overlaps are properly set up.
     * The last N lines of chunk N should match the first N lines of chunk N+1.
     */
    private fun reconcileOverlaps(chunks: List<CodeChunk>): List<CodeChunk> {
        if (chunks.size <= 1) {
            return chunks
        }

        val reconciled = mutableListOf<CodeChunk>()

        for (i in chunks.indices) {
            val chunk = chunks[i]

            if (i < chunks.size - 1) {
                val nextChunk = chunks[i + 1]
                // The overlap is implicitly handled by the ranges
                // Just ensure they're properly tracked
            }

            reconciled.add(chunk)
        }

        return reconciled
    }

    /**
     * Determines if the file extension represents a code language.
     */
    private fun isCodeLanguage(extension: String): Boolean {
        val normalized = extension.lowercase()
        return CODE_EXTENSIONS.contains(normalized)
    }
}
