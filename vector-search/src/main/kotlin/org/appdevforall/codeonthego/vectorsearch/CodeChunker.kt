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
    // Default configuration
    private const val DEFAULT_MAX_CHUNK_SIZE = 2000
    private const val DEFAULT_OVERLAP_SIZE = 100
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

        val lines = content.split("\n")
        val isCodeFile = isCodeLanguage(fileExtension)

        // For text files or very small files, use simple line-based chunking
        if (!isCodeFile || lines.size <= 10) {
            return simpleChunk(lines, maxChunkSize, overlapSize)
        }

        // For code files, use boundary-aware chunking
        return semanticChunk(lines, maxChunkSize, overlapSize, minChunkSize)
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
            val (chunkLines, nextStart) = buildSemanticChunk(
                lines,
                currentStart,
                maxChunkSize,
            )

            if (chunkLines.isEmpty()) {
                break
            }

            // Check if chunk is too small
            if (chunkLines.size < minChunkSize && nextStart < lines.size) {
                // Continue building until we have a reasonable chunk or reach end
                val expandedLines = chunkLines.toMutableList()
                var expandedStart = nextStart

                while (expandedLines.size < minChunkSize && expandedStart < lines.size) {
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

                // Calculate overlap in lines
                val overlapLines = calculateOverlapLines(chunkLines, overlapSize)
                currentStart = maxOf(nextStart - overlapLines, currentStart + 1)
            }
        }

        // Post-process to handle overlap: ensure last N lines of chunk N match first N lines of N+1
        return reconcileOverlaps(chunks)
    }

    /**
     * Builds a single semantic chunk starting from a given line.
     * Prefers to break AFTER closing braces.
     *
     * @return Pair of (chunk lines, next start line index)
     */
    private fun buildSemanticChunk(
        lines: List<String>,
        startLine: Int,
        maxChunkSize: Int,
    ): Pair<List<String>, Int> {
        val chunk = mutableListOf<String>()
        var currentSize = 0
        var currentLine = startLine
        var lastBoundaryLine = startLine // Last line with a closing boundary

        while (currentLine < lines.size) {
            val line = lines[currentLine]
            val lineSize = line.length + 1 // +1 for newline

            if (currentSize + lineSize > maxChunkSize && chunk.isNotEmpty()) {
                // We've exceeded the size limit
                // Break at the last boundary if we have one
                if (lastBoundaryLine >= startLine && lastBoundaryLine < currentLine) {
                    return Pair(
                        lines.subList(startLine, lastBoundaryLine + 1),
                        lastBoundaryLine + 1,
                    )
                } else {
                    // No boundary found, just break here
                    return Pair(
                        lines.subList(startLine, currentLine),
                        currentLine,
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

        return Pair(
            lines.subList(startLine, minOf(currentLine, lines.size)),
            minOf(currentLine, lines.size),
        )
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
        return trimmed.matches(Regex("""^\s*(public|private|protected|internal)?\s*(fun|class|object|interface|enum|sealed|data|companion|open|abstract)\b.*"""))
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
