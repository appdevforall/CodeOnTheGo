package org.appdevforall.codeonthego.lsp.kotlin.parser

import com.itsaky.androidide.treesitter.TSTree
import java.io.Closeable

/**
 * Immutable wrapper around a tree-sitter parse tree.
 *
 * SyntaxTree represents a complete parsed Kotlin source file. It holds:
 * - The parsed tree structure
 * - The original source text (for node text extraction)
 * - Metadata about parse errors
 *
 * ## Lifecycle Management
 *
 * Tree-sitter trees allocate native memory. This class implements [Closeable]
 * to ensure proper cleanup:
 *
 * ```kotlin
 * parser.parse(source).tree.use { tree ->
 *     // Use tree...
 * } // Tree is automatically closed
 * ```
 *
 * For incremental parsing, the old tree must remain open until the new tree is created:
 *
 * ```kotlin
 * val tree1 = parser.parse(source1)
 * val tree2 = parser.parseIncremental(source2, tree1.tree, edit)
 * tree1.tree.close() // Now safe to close
 * ```
 *
 * ## Immutability
 *
 * Once created, a SyntaxTree cannot be modified. To get a tree reflecting edits,
 * use [KotlinParser.parseIncremental] which creates a new tree.
 *
 * ## Thread Safety
 *
 * Reading from a SyntaxTree is thread-safe. Multiple threads can navigate
 * the same tree concurrently. However, trees should not be shared across
 * threads if using incremental parsing, as tree-sitter's edit tracking
 * is not thread-safe.
 *
 * @property tree The underlying tree-sitter tree
 * @property source The original source text
 * @property languageVersion The tree-sitter language version used
 */
class SyntaxTree internal constructor(
    internal val tree: TSTree,
    private val source: String,
    val languageVersion: Int = 0
) : Closeable {

    /**
     * The root node of the syntax tree.
     *
     * For a well-formed Kotlin file, this will be a SOURCE_FILE node.
     */
    val root: SyntaxNode by lazy {
        SyntaxNode(tree.rootNode, source)
    }

    /**
     * The original source text that was parsed.
     */
    val sourceText: String get() = source

    /**
     * The length of the source text in bytes.
     */
    val sourceLength: Int get() = source.length

    /**
     * Whether this tree contains any syntax errors.
     *
     * Even with errors, tree-sitter produces a "best effort" tree.
     * Error nodes are marked with [SyntaxNode.isError].
     */
    val hasErrors: Boolean by lazy {
        root.hasError
    }

    /**
     * Collects all error nodes in the tree.
     *
     * Error nodes represent portions of the source that couldn't be parsed
     * according to the grammar. Each error node has a [SyntaxNode.range]
     * indicating where the error occurred.
     *
     * @return List of error nodes in document order
     */
    val errors: List<SyntaxNode> by lazy {
        root.findAll { it.isError || it.isMissing }.toList()
    }

    /**
     * Finds the deepest node at a given position.
     *
     * @param position The position to query
     * @return The deepest node containing that position, or null if outside tree
     */
    fun nodeAtPosition(position: Position): SyntaxNode? {
        return root.nodeAtPosition(position)
    }

    /**
     * Finds the deepest named node at a given position.
     *
     * Named nodes represent meaningful syntax elements (not punctuation).
     *
     * @param position The position to query
     * @return The deepest named node at that position
     */
    fun namedNodeAtPosition(position: Position): SyntaxNode? {
        return root.namedNodeAtPosition(position)
    }

    /**
     * Gets all nodes that overlap with a given range.
     *
     * @param range The range to query
     * @return Sequence of nodes overlapping the range
     */
    fun nodesInRange(range: TextRange): Sequence<SyntaxNode> {
        return root.findAll { it.range.overlaps(range) }
    }

    /**
     * Converts a byte offset to a [Position].
     *
     * @param byteOffset Byte offset in the source
     * @return Position (line and column)
     */
    fun positionFromOffset(byteOffset: Int): Position {
        require(byteOffset >= 0) { "Byte offset must be non-negative" }
        require(byteOffset <= source.length) { "Byte offset $byteOffset exceeds source length ${source.length}" }

        var line = 0
        var lineStart = 0

        for (i in 0 until byteOffset) {
            if (i < source.length && source[i] == '\n') {
                line++
                lineStart = i + 1
            }
        }

        return Position(line = line, column = byteOffset - lineStart)
    }

    /**
     * Converts a [Position] to a byte offset.
     *
     * @param position The position (line and column)
     * @return Byte offset in the source
     */
    fun offsetFromPosition(position: Position): Int {
        var offset = 0
        var currentLine = 0

        while (currentLine < position.line && offset < source.length) {
            if (source[offset] == '\n') {
                currentLine++
            }
            offset++
        }

        return offset + position.column
    }

    /**
     * Extracts a substring from the source using a [TextRange].
     *
     * @param range The range to extract
     * @return The source text in that range
     */
    fun textInRange(range: TextRange): String {
        val startOffset = offsetFromPosition(range.start)
        val endOffset = offsetFromPosition(range.end)
        return source.substring(
            startOffset.coerceIn(0, source.length),
            endOffset.coerceIn(0, source.length)
        )
    }

    /**
     * Returns the S-expression representation of the entire tree.
     *
     * Useful for debugging and testing.
     */
    fun toSexp(): String = root.toSexp()

    /**
     * Creates a copy of this tree for concurrent access.
     *
     * Tree-sitter trees can be copied for use in different threads.
     * The copy shares structure with the original but can be
     * accessed independently.
     *
     * @return A new SyntaxTree backed by a copied tree
     */
    fun copy(): SyntaxTree {
        return SyntaxTree(tree.copy(), source, languageVersion)
    }

    /**
     * Releases native resources held by this tree.
     *
     * After calling close(), the tree should not be used.
     * Accessing nodes from a closed tree results in undefined behavior.
     */
    override fun close() {
        tree.close()
    }

    override fun toString(): String {
        return "SyntaxTree(hasErrors=$hasErrors, lines=${root.endLine + 1})"
    }
}
