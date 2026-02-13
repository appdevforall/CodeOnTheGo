package org.appdevforall.codeonthego.lsp.kotlin.server

import android.util.Log
import org.appdevforall.codeonthego.lsp.kotlin.index.FileIndex
import org.appdevforall.codeonthego.lsp.kotlin.parser.ParseResult
import org.appdevforall.codeonthego.lsp.kotlin.parser.PositionConverter
import org.appdevforall.codeonthego.lsp.kotlin.parser.SyntaxTree
import org.appdevforall.codeonthego.lsp.kotlin.semantic.AnalysisContext
import org.appdevforall.codeonthego.lsp.kotlin.semantic.Diagnostic
import org.appdevforall.codeonthego.lsp.kotlin.semantic.DiagnosticSeverity
import org.appdevforall.codeonthego.lsp.kotlin.symbol.SymbolTable

private const val TAG = "DocumentState"

/**
 * Per-document analysis state.
 *
 * DocumentState holds all analysis artifacts for a single open document,
 * including the parse tree, symbol table, diagnostics, and file index.
 *
 * ## Lifecycle
 *
 * ```
 * EMPTY -> PARSED -> ANALYZED
 *   ^                   |
 *   +-------------------+
 *        (on edit)
 * ```
 *
 * @property uri Document URI
 * @property version Document version (increments on each edit)
 * @property content Current document text
 */
class DocumentState(
    val uri: String,
    var version: Int = 0,
    var content: String = ""
) {
    var parseResult: ParseResult? = null
        private set

    var syntaxTree: SyntaxTree? = null
        private set

    var symbolTable: SymbolTable? = null
        private set

    var analysisContext: AnalysisContext? = null
        private set

    var fileIndex: FileIndex? = null
        private set

    var diagnostics: List<Diagnostic> = emptyList()
        private set

    var lastModified: Long = System.currentTimeMillis()
        private set

    val isParsed: Boolean get() = parseResult != null
    val isAnalyzed: Boolean get() = symbolTable != null
    val hasErrors: Boolean get() = diagnostics.any { it.severity == DiagnosticSeverity.ERROR }

    val filePath: String get() = uriToPath(uri)

    val packageName: String get() = symbolTable?.packageName ?: ""

    val lineCount: Int get() = content.count { it == '\n' } + 1

    /**
     * Updates document content and invalidates analysis.
     */
    fun updateContent(newContent: String, newVersion: Int) {
        content = newContent
        version = newVersion
        lastModified = System.currentTimeMillis()
        invalidate()
    }

    /**
     * Applies an incremental text change.
     */
    fun applyChange(
        startLine: Int,
        startChar: Int,
        endLine: Int,
        endChar: Int,
        newText: String,
        newVersion: Int
    ) {
        val startOffset = positionToOffset(startLine, startChar)
        val endOffset = positionToOffset(endLine, endChar)

        val oldLength = content.length
        val deletedText = if (startOffset < endOffset && endOffset <= content.length) {
            content.substring(startOffset, endOffset)
        } else {
            "<invalid range>"
        }

        Log.d(TAG, "[DOC-SYNC] applyChange: uri=$uri, range=$startLine:$startChar-$endLine:$endChar")
        Log.d(TAG, "[DOC-SYNC]   offsets: start=$startOffset, end=$endOffset, contentLength=$oldLength")
        Log.d(TAG, "[DOC-SYNC]   deleted='$deletedText', newText='$newText' (${newText.length} chars)")

        content = content.substring(0, startOffset) + newText + content.substring(endOffset)

        Log.d(TAG, "[DOC-SYNC]   result: oldLen=$oldLength, newLen=${content.length}, version=$newVersion")
        Log.d(TAG, "[DOC-SYNC]   content near edit: '${content.substring(maxOf(0, startOffset - 10), minOf(content.length, startOffset + 20)).replace("\n", "\\n")}'")

        version = newVersion
        lastModified = System.currentTimeMillis()
        invalidate()
    }

    /**
     * Applies an incremental text change using direct character indices.
     * This bypasses line/column to offset conversion for more reliable sync.
     */
    fun applyChangeByIndex(
        startIndex: Int,
        endIndex: Int,
        newText: String,
        newVersion: Int
    ) {
        val safeStart = maxOf(0, minOf(startIndex, content.length))
        val safeEnd = maxOf(safeStart, minOf(endIndex, content.length))

        val oldLength = content.length
        val deletedText = if (safeStart < safeEnd) {
            content.substring(safeStart, safeEnd)
        } else {
            ""
        }

        Log.d(TAG, "[DOC-SYNC] applyChangeByIndex: uri=$uri, indices=$startIndex-$endIndex")
        Log.d(TAG, "[DOC-SYNC]   safeIndices: start=$safeStart, end=$safeEnd, contentLength=$oldLength")
        Log.d(TAG, "[DOC-SYNC]   deleted='$deletedText', newText='$newText' (${newText.length} chars)")

        content = content.substring(0, safeStart) + newText + content.substring(safeEnd)

        Log.d(TAG, "[DOC-SYNC]   result: oldLen=$oldLength, newLen=${content.length}, version=$newVersion")
        Log.d(TAG, "[DOC-SYNC]   content near edit: '${content.substring(maxOf(0, safeStart - 10), minOf(content.length, safeStart + 20)).replace("\n", "\\n")}'")

        version = newVersion
        lastModified = System.currentTimeMillis()
        invalidate()
    }

    /**
     * Sets parse result after parsing.
     */
    fun setParsed(result: ParseResult) {
        parseResult = result
        syntaxTree = result.tree
    }

    /**
     * Sets analysis results after semantic analysis.
     */
    fun setAnalyzed(
        table: SymbolTable,
        context: AnalysisContext,
        index: FileIndex,
        diags: List<Diagnostic>
    ) {
        symbolTable = table
        analysisContext = context
        fileIndex = index
        diagnostics = diags
    }

    /**
     * Invalidates analysis state, preserving diagnostics until new analysis completes.
     *
     * Diagnostics are intentionally NOT cleared here - they remain visible
     * until [setAnalyzed] is called with fresh analysis results. This prevents
     * the "diagnostic flicker" where errors disappear during typing and only
     * reappear after debounce + analysis time.
     */
    fun invalidate() {
        parseResult = null
        syntaxTree = null
        symbolTable = null
        analysisContext = null
        fileIndex = null
    }

    /**
     * Explicitly clears diagnostics. Use only when closing documents.
     */
    fun clearDiagnostics() {
        diagnostics = emptyList()
    }

    /**
     * Converts line/character position to string offset.
     * The character parameter is in UTF-16 code units (LSP specification).
     * For Java/Kotlin strings, UTF-16 code units equal the string index for BMP chars.
     */
    fun positionToOffset(line: Int, character: Int): Int {
        if (content.isEmpty()) return 0

        var currentLine = 0
        var lineStart = 0

        for (i in content.indices) {
            if (currentLine == line) {
                break
            }
            if (content[i] == '\n') {
                currentLine++
                lineStart = i + 1
            }
        }

        if (currentLine != line) {
            return content.length
        }

        return minOf(lineStart + character, content.length)
    }

    /**
     * Converts string offset to line/character position.
     * Returns UTF-16 code unit offset for the column (LSP specification).
     * For Java/Kotlin strings, the string index equals UTF-16 code units for BMP chars.
     */
    fun offsetToPosition(offset: Int): Pair<Int, Int> {
        val (line, charColumn) = PositionConverter.charOffsetToLineAndColumn(content, offset)
        return line to charColumn
    }

    /**
     * Gets the line text at a given line number.
     */
    fun getLine(lineNumber: Int): String {
        val lines = content.split('\n')
        return if (lineNumber in lines.indices) lines[lineNumber] else ""
    }

    /**
     * Gets text in a range.
     */
    fun getText(startLine: Int, startChar: Int, endLine: Int, endChar: Int): String {
        val start = positionToOffset(startLine, startChar)
        val end = positionToOffset(endLine, endChar)
        return content.substring(start, end)
    }

    /**
     * Gets the word at a position.
     */
    fun getWordAt(line: Int, character: Int): String? {
        val offset = positionToOffset(line, character)
        if (offset >= content.length) return null

        var start = offset
        var end = offset

        while (start > 0 && isIdentifierChar(content[start - 1])) {
            start--
        }

        while (end < content.length && isIdentifierChar(content[end])) {
            end++
        }

        return if (start < end) content.substring(start, end) else null
    }

    private fun isIdentifierChar(c: Char): Boolean {
        return c.isLetterOrDigit() || c == '_'
    }

    override fun toString(): String {
        return "DocumentState(uri=$uri, version=$version, parsed=$isParsed, analyzed=$isAnalyzed)"
    }

    companion object {
        fun uriToPath(uri: String): String {
            return uri
                .removePrefix("file://")
                .removePrefix("file:")
                .replace("%20", " ")
        }

        fun pathToUri(path: String): String {
            return "file://$path"
        }
    }
}

/**
 * Analysis phase for tracking progress.
 */
enum class AnalysisPhase {
    NONE,
    PARSING,
    PARSED,
    ANALYZING,
    ANALYZED
}
