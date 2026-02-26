package org.appdevforall.codeonthego.lsp.kotlin.parser

/**
 * Result of parsing Kotlin source code.
 *
 * Contains both the syntax tree and metadata about the parse operation.
 * Even when parsing fails (syntax errors), a tree is still produced
 * with error recovery.
 *
 * ## Error Handling
 *
 * Tree-sitter uses error recovery to produce a tree even for malformed input.
 * The tree will contain ERROR nodes where the parser couldn't match the grammar.
 * This allows the LSP to provide functionality even while code is being edited.
 *
 * ## Usage
 *
 * ```kotlin
 * val result = parser.parse(source)
 *
 * if (result.hasErrors) {
 *     for (error in result.syntaxErrors) {
 *         println("Syntax error at ${error.range}: ${error.message}")
 *     }
 * }
 *
 * // Tree is usable regardless of errors
 * val functions = result.tree.root.findAllByKind(SyntaxKind.FUNCTION_DECLARATION)
 * ```
 *
 * ## Resource Management
 *
 * The [tree] holds native resources. Use `use` blocks for automatic cleanup:
 *
 * ```kotlin
 * parser.parse(source).use { result ->
 *     // Use result.tree...
 * } // Tree is closed automatically
 * ```
 *
 * @property tree The parsed syntax tree
 * @property syntaxErrors List of syntax errors found during parsing
 * @property parseTimeMs Time taken to parse, in milliseconds
 */
data class ParseResult(
    val tree: SyntaxTree,
    val syntaxErrors: List<SyntaxError>,
    val parseTimeMs: Long
) : AutoCloseable {

    /**
     * Whether any syntax errors were found.
     */
    val hasErrors: Boolean get() = syntaxErrors.isNotEmpty()

    /**
     * The number of syntax errors found.
     */
    val errorCount: Int get() = syntaxErrors.size

    /**
     * Whether the parse succeeded without any errors.
     */
    val isValid: Boolean get() = !hasErrors

    /**
     * Closes the underlying syntax tree and releases resources.
     */
    override fun close() {
        tree.close()
    }
}

/**
 * Represents a syntax error found during parsing.
 *
 * Syntax errors occur when the source doesn't match the Kotlin grammar.
 * Tree-sitter recovers from errors and continues parsing, so multiple
 * errors may be reported for a single parse.
 *
 * @property range Where the error occurred in the source
 * @property message Human-readable description of the error
 * @property errorNodeText The problematic text that caused the error
 */
data class SyntaxError(
    val range: TextRange,
    val message: String,
    val errorNodeText: String
) {
    /**
     * One-based line number for display.
     */
    val line: Int get() = range.start.displayLine

    /**
     * One-based column number for display.
     */
    val column: Int get() = range.start.displayColumn

    override fun toString(): String {
        return "SyntaxError(${range.start}: $message)"
    }

    companion object {
        /**
         * Creates a SyntaxError from an error node in the tree.
         *
         * @param node The ERROR or MISSING node from tree-sitter
         * @return A SyntaxError with details about the parse failure
         */
        fun fromNode(node: SyntaxNode): SyntaxError {
            val message = when {
                node.isMissing -> "Missing ${formatNodeType(node.type)}"
                node.kind == SyntaxKind.ERROR -> "Unexpected syntax"
                else -> "Syntax error"
            }

            val errorRange = narrowErrorRange(node)

            return SyntaxError(
                range = errorRange,
                message = message,
                errorNodeText = node.text.take(50)
            )
        }

        private fun narrowErrorRange(node: SyntaxNode): TextRange {
            if (node.isMissing) {
                return node.range
            }

            val missingNode = findFirstMissingNode(node)
            if (missingNode != null) {
                return missingNode.range
            }

            val firstErrorChild = findFirstErrorChild(node)
            if (firstErrorChild != null && firstErrorChild !== node) {
                return narrowErrorRange(firstErrorChild)
            }

            val start = node.range.start
            val end = if (node.range.end.line == start.line) {
                Position(start.line, minOf(start.column + 20, node.range.end.column))
            } else {
                Position(start.line, start.column + 1)
            }

            return TextRange(start, end)
        }

        private fun findFirstMissingNode(node: SyntaxNode): SyntaxNode? {
            if (node.isMissing) {
                return node
            }
            for (child in node.children) {
                val found = findFirstMissingNode(child)
                if (found != null) {
                    return found
                }
            }
            return null
        }

        private fun findFirstErrorChild(node: SyntaxNode): SyntaxNode? {
            for (child in node.children) {
                if (child.kind == SyntaxKind.ERROR) {
                    return child
                }
                val nested = findFirstErrorChild(child)
                if (nested != null) {
                    return nested
                }
            }
            return null
        }

        /**
         * Formats a tree-sitter node type for display.
         *
         * Converts snake_case to readable text: "function_declaration" → "function declaration"
         */
        private fun formatNodeType(type: String): String {
            return type.replace('_', ' ')
        }
    }
}

/**
 * Represents an edit to be applied before incremental parsing.
 *
 * When source code is modified, describe the edit using this class
 * and pass it to [KotlinParser.parseIncremental] for efficient re-parsing.
 *
 * ## Byte Offsets
 *
 * All offsets are in bytes, not characters. For ASCII text these are the same,
 * but for UTF-8 text with multi-byte characters, byte counts may differ.
 *
 * ## Example
 *
 * Inserting "hello" at byte position 10:
 * ```kotlin
 * val edit = SourceEdit(
 *     startByte = 10,
 *     oldEndByte = 10,       // No text replaced (insertion)
 *     newEndByte = 15,       // 5 bytes inserted
 *     startPosition = Position(0, 10),
 *     oldEndPosition = Position(0, 10),
 *     newEndPosition = Position(0, 15)
 * )
 * ```
 *
 * Replacing "foo" (3 bytes) with "bar" (3 bytes) at position 10:
 * ```kotlin
 * val edit = SourceEdit(
 *     startByte = 10,
 *     oldEndByte = 13,       // Old text was 3 bytes
 *     newEndByte = 13,       // New text is also 3 bytes
 *     startPosition = Position(0, 10),
 *     oldEndPosition = Position(0, 13),
 *     newEndPosition = Position(0, 13)
 * )
 * ```
 *
 * @property startByte Byte offset where the edit starts
 * @property oldEndByte Byte offset where the OLD text ended (before edit)
 * @property newEndByte Byte offset where the NEW text ends (after edit)
 * @property startPosition Position where the edit starts
 * @property oldEndPosition Position where the old text ended
 * @property newEndPosition Position where the new text ends
 */
data class SourceEdit(
    val startByte: Int,
    val oldEndByte: Int,
    val newEndByte: Int,
    val startPosition: Position,
    val oldEndPosition: Position,
    val newEndPosition: Position
) {
    init {
        require(startByte >= 0) { "startByte must be non-negative" }
        require(oldEndByte >= startByte) { "oldEndByte must be >= startByte" }
        require(newEndByte >= startByte) { "newEndByte must be >= startByte" }
    }

    /**
     * Number of bytes in the old (replaced) text.
     */
    val oldLength: Int get() = oldEndByte - startByte

    /**
     * Number of bytes in the new (replacement) text.
     */
    val newLength: Int get() = newEndByte - startByte

    /**
     * Whether this edit is an insertion (no old text replaced).
     */
    val isInsertion: Boolean get() = oldLength == 0

    /**
     * Whether this edit is a deletion (no new text inserted).
     */
    val isDeletion: Boolean get() = newLength == 0

    /**
     * Whether this edit replaces text (both old and new lengths > 0).
     */
    val isReplacement: Boolean get() = oldLength > 0 && newLength > 0

    companion object {
        /**
         * Creates an edit representing an insertion.
         *
         * @param position Where to insert
         * @param byteOffset Byte offset of insertion point
         * @param insertedLength Length of inserted text in bytes
         */
        fun insertion(position: Position, byteOffset: Int, insertedLength: Int): SourceEdit {
            return SourceEdit(
                startByte = byteOffset,
                oldEndByte = byteOffset,
                newEndByte = byteOffset + insertedLength,
                startPosition = position,
                oldEndPosition = position,
                newEndPosition = position.copy(column = position.column + insertedLength)
            )
        }

        /**
         * Creates an edit representing a deletion.
         *
         * @param startPosition Start of deleted range
         * @param endPosition End of deleted range
         * @param startByte Byte offset of deletion start
         * @param endByte Byte offset of deletion end
         */
        fun deletion(
            startPosition: Position,
            endPosition: Position,
            startByte: Int,
            endByte: Int
        ): SourceEdit {
            return SourceEdit(
                startByte = startByte,
                oldEndByte = endByte,
                newEndByte = startByte,
                startPosition = startPosition,
                oldEndPosition = endPosition,
                newEndPosition = startPosition
            )
        }
    }
}
