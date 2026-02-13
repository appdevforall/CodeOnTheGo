package org.appdevforall.codeonthego.lsp.kotlin.parser

import com.itsaky.androidide.treesitter.TSNode

/**
 * Immutable wrapper around a tree-sitter syntax node.
 *
 * This class provides a Kotlin-idiomatic API for navigating and querying syntax trees.
 * It wraps the tree-sitter [TSNode] to provide:
 * - Type-safe node kinds via [SyntaxKind]
 * - Cached text extraction
 * - Convenient navigation methods
 * - Position/range utilities
 *
 * ## Design Philosophy
 *
 * This wrapper exists to:
 * 1. **Isolate tree-sitter**: Changes to tree-sitter API only affect this class
 * 2. **Add type safety**: [SyntaxKind] enum instead of raw strings
 * 3. **Improve ergonomics**: Kotlin idioms like sequences and null safety
 * 4. **Enable caching**: Text and children can be cached for repeated access
 *
 * ## Memory Model
 *
 * SyntaxNode instances are lightweight wrappers. The underlying tree-sitter [TSNode]
 * references data in the [SyntaxTree], which must remain alive while nodes are used.
 * Child nodes are created lazily when accessed.
 *
 * ## Example
 *
 * ```kotlin
 * val root = tree.root
 * val functions = root.children
 *     .filter { it.kind == SyntaxKind.FUNCTION_DECLARATION }
 *     .map { it.childByFieldName("name")?.text }
 * ```
 *
 * @property node The underlying tree-sitter node
 * @property source The complete source text (for text extraction)
 */
class SyntaxNode internal constructor(
    internal val node: TSNode,
    private val source: String
) {
    companion object {
        private fun TSNode.isNullOrInvalid(): Boolean {
            return try {
                this.isNull
            } catch (e: IllegalStateException) {
                true
            }
        }
    }
    /**
     * The type of this syntax node as a [SyntaxKind].
     *
     * Unknown tree-sitter types map to [SyntaxKind.UNKNOWN].
     */
    val kind: SyntaxKind by lazy {
        try {
            SyntaxKind.fromTreeSitter(node.type)
        } catch (e: IllegalStateException) {
            SyntaxKind.UNKNOWN
        }
    }

    /**
     * The raw tree-sitter type string.
     *
     * Use [kind] for type-safe access. This property is useful for debugging
     * or handling nodes not yet in [SyntaxKind].
     */
    val type: String
        get() = try { node.type } catch (e: IllegalStateException) { "ERROR" }

    /**
     * The source text covered by this node.
     *
     * AndroidIDE's tree-sitter uses UTF-16 byte offsets (2 bytes per BMP char).
     * We use PositionConverter to properly handle the conversion.
     */
    val text: String by lazy {
        try {
            PositionConverter.extractSubstring(source, node.startByte, node.endByte)
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * The text range (start and end positions) of this node.
     *
     * Uses tree-sitter's row directly and divides column by 2
     * to convert UTF-16 byte offsets to character positions.
     * Also includes character offsets for accurate index calculation.
     */
    val range: TextRange by lazy {
        try {
            TextRange(
                start = Position(
                    line = node.startPoint.row,
                    column = node.startPoint.column / 2
                ),
                end = Position(
                    line = node.endPoint.row,
                    column = node.endPoint.column / 2
                ),
                startOffset = startOffset,
                endOffset = endOffset
            )
        } catch (e: IllegalStateException) {
            TextRange(Position(0, 0), Position(0, 0))
        }
    }

    /** Zero-based start line */
    val startLine: Int
        get() = try { node.startPoint.row } catch (e: IllegalStateException) { 0 }

    /** Zero-based end line */
    val endLine: Int
        get() = try { node.endPoint.row } catch (e: IllegalStateException) { 0 }

    /** Zero-based start column (UTF-16 bytes / 2 for character position) */
    val startColumn: Int
        get() = try { node.startPoint.column / 2 } catch (e: IllegalStateException) { 0 }

    /** Zero-based end column (UTF-16 bytes / 2 for character position) */
    val endColumn: Int
        get() = try { node.endPoint.column / 2 } catch (e: IllegalStateException) { 0 }

    /** Byte offset of start position in source (raw from tree-sitter, UTF-16 based) */
    val startByte: Int
        get() = try { node.startByte } catch (e: IllegalStateException) { 0 }

    /** Byte offset of end position in source (raw from tree-sitter, UTF-16 based) */
    val endByte: Int
        get() = try { node.endByte } catch (e: IllegalStateException) { 0 }

    /** Character offset of start position in source */
    val startOffset: Int
        get() = PositionConverter.byteOffsetToCharIndex(source, startByte)

    /** Character offset of end position in source */
    val endOffset: Int
        get() = PositionConverter.byteOffsetToCharIndex(source, endByte)

    /**
     * The parent node, or null if this is the root.
     */
    val parent: SyntaxNode?
        get() {
            val p = node.parent
            return if (p.isNullOrInvalid()) null else SyntaxNode(p, source)
        }

    /**
     * All child nodes, including anonymous (punctuation, operators) nodes.
     *
     * Use [namedChildren] if you only want significant syntax elements.
     */
    val children: List<SyntaxNode> by lazy {
        try {
            (0 until node.childCount).mapNotNull { index ->
                try {
                    val child = node.getChild(index)
                    if (child.isNullOrInvalid()) null else SyntaxNode(child, source)
                } catch (e: IllegalStateException) {
                    null
                }
            }
        } catch (e: IllegalStateException) {
            emptyList()
        }
    }

    /**
     * Only "named" child nodes (excludes punctuation and anonymous nodes).
     *
     * Named nodes are significant syntax elements like declarations,
     * expressions, and identifiers. Unnamed nodes are punctuation,
     * operators, and other grammar artifacts.
     */
    val namedChildren: List<SyntaxNode> by lazy {
        try {
            (0 until node.namedChildCount).mapNotNull { index ->
                try {
                    val child = node.getNamedChild(index)
                    if (child.isNullOrInvalid()) null else SyntaxNode(child, source)
                } catch (e: IllegalStateException) {
                    null
                }
            }
        } catch (e: IllegalStateException) {
            emptyList()
        }
    }

    /** Total number of children (including anonymous) */
    val childCount: Int
        get() = try { node.childCount } catch (e: IllegalStateException) { 0 }

    /** Number of named children */
    val namedChildCount: Int
        get() = try { node.namedChildCount } catch (e: IllegalStateException) { 0 }

    /**
     * Whether this is a "named" node (significant syntax element).
     *
     * Named nodes represent meaningful language constructs.
     * Anonymous nodes are grammar artifacts like punctuation.
     */
    val isNamed: Boolean
        get() = try { node.isNamed } catch (e: IllegalStateException) { false }

    /**
     * Whether this node represents a syntax error.
     */
    val isError: Boolean
        get() = try { node.isError } catch (e: IllegalStateException) { true }

    /**
     * Whether this node is missing from the source (inserted by error recovery).
     *
     * Tree-sitter may insert missing nodes to produce a valid parse tree
     * when the source has syntax errors.
     */
    val isMissing: Boolean
        get() = try { node.isMissing } catch (e: IllegalStateException) { true }

    /**
     * Whether this node or any descendant contains a syntax error.
     */
    val hasError: Boolean
        get() = try { node.hasErrors() } catch (e: IllegalStateException) { true }

    /**
     * The next sibling node (same parent), or null if last child.
     */
    val nextSibling: SyntaxNode?
        get() {
            val sibling = node.nextSibling
            return if (sibling.isNullOrInvalid()) null else SyntaxNode(sibling, source)
        }

    /**
     * The previous sibling node, or null if first child.
     */
    val prevSibling: SyntaxNode?
        get() {
            val sibling = node.getPreviousSibling()
            return if (sibling.isNullOrInvalid()) null else SyntaxNode(sibling, source)
        }

    /**
     * The next named sibling, or null if no more named siblings.
     */
    val nextNamedSibling: SyntaxNode?
        get() {
            val sibling = node.nextNamedSibling
            return if (sibling.isNullOrInvalid()) null else SyntaxNode(sibling, source)
        }

    /**
     * The previous named sibling, or null if no previous named siblings.
     */
    val prevNamedSibling: SyntaxNode?
        get() {
            val sibling = node.getPreviousNamedSibling()
            return if (sibling.isNullOrInvalid()) null else SyntaxNode(sibling, source)
        }

    /**
     * The first child node, or null if no children.
     */
    val firstChild: SyntaxNode?
        get() = if (childCount > 0) child(0) else null

    /**
     * The last child node, or null if no children.
     */
    val lastChild: SyntaxNode?
        get() = if (childCount > 0) child(childCount - 1) else null

    /**
     * The first named child, or null if no named children.
     */
    val firstNamedChild: SyntaxNode?
        get() = if (namedChildCount > 0) namedChild(0) else null

    /**
     * The last named child, or null if no named children.
     */
    val lastNamedChild: SyntaxNode?
        get() = if (namedChildCount > 0) namedChild(namedChildCount - 1) else null

    /**
     * Gets a child by index.
     *
     * @param index Zero-based child index
     * @return The child node, or null if index is out of bounds
     */
    fun child(index: Int): SyntaxNode? {
        return try {
            if (index in 0 until childCount) {
                val child = node.getChild(index)
                if (child.isNullOrInvalid()) null else SyntaxNode(child, source)
            } else {
                null
            }
        } catch (e: IllegalStateException) {
            null
        }
    }

    /**
     * Gets a named child by index.
     *
     * @param index Zero-based index among named children only
     * @return The named child, or null if index is out of bounds
     */
    fun namedChild(index: Int): SyntaxNode? {
        return try {
            if (index in 0 until namedChildCount) {
                val child = node.getNamedChild(index)
                if (child.isNullOrInvalid()) null else SyntaxNode(child, source)
            } else {
                null
            }
        } catch (e: IllegalStateException) {
            null
        }
    }

    /**
     * Gets a child by its field name in the grammar.
     *
     * Field names provide semantic access to node parts. For example,
     * a function declaration has fields like "name", "parameters", "body".
     *
     * @param fieldName The grammar field name
     * @return The child at that field, or null if not present
     */
    fun childByFieldName(fieldName: String): SyntaxNode? {
        return try {
            val child = node.getChildByFieldName(fieldName)
            if (child.isNullOrInvalid()) null else SyntaxNode(child, source)
        } catch (e: IllegalStateException) {
            null
        }
    }

    /**
     * Finds the first child with the given kind.
     *
     * @param kind The [SyntaxKind] to find
     * @return The first matching child, or null if not found
     */
    fun findChild(kind: SyntaxKind): SyntaxNode? {
        return children.find { it.kind == kind }
    }

    /**
     * Finds all children with the given kind.
     *
     * @param kind The [SyntaxKind] to find
     * @return List of all matching children
     */
    fun findChildren(kind: SyntaxKind): List<SyntaxNode> {
        return children.filter { it.kind == kind }
    }

    /**
     * Finds all descendants matching a predicate.
     *
     * Traverses the tree depth-first.
     *
     * @param predicate Function that tests each node
     * @return Sequence of matching nodes
     */
    fun findAll(predicate: (SyntaxNode) -> Boolean): Sequence<SyntaxNode> = sequence {
        if (predicate(this@SyntaxNode)) {
            yield(this@SyntaxNode)
        }
        for (child in children) {
            yieldAll(child.findAll(predicate))
        }
    }

    /**
     * Finds all descendants of a specific kind.
     *
     * @param kind The [SyntaxKind] to find
     * @return Sequence of all descendants with matching kind
     */
    fun findAllByKind(kind: SyntaxKind): Sequence<SyntaxNode> {
        return findAll { it.kind == kind }
    }

    /**
     * Finds the deepest node containing the given position.
     *
     * @param position The position to find
     * @return The deepest node containing that position, or null if outside
     */
    fun nodeAtPosition(position: Position): SyntaxNode? {
        if (position !in range) return null

        for (child in children) {
            val found = child.nodeAtPosition(position)
            if (found != null) return found
        }
        return this
    }

    /**
     * Finds the deepest named node containing the given position.
     *
     * This is useful for finding meaningful syntax elements at a cursor position.
     *
     * @param position The position to find
     * @return The deepest named node at that position, or null if outside
     */
    fun namedNodeAtPosition(position: Position): SyntaxNode? {
        if (position !in range) return null

        for (child in namedChildren) {
            val found = child.namedNodeAtPosition(position)
            if (found != null) return found
        }
        return if (isNamed) this else null
    }

    /**
     * Walks up the tree to find the first ancestor matching a predicate.
     *
     * @param predicate Function that tests each ancestor
     * @return The first matching ancestor, or null if none match
     */
    fun findAncestor(predicate: (SyntaxNode) -> Boolean): SyntaxNode? {
        var current = parent
        while (current != null) {
            if (predicate(current)) return current
            current = current.parent
        }
        return null
    }

    /**
     * Walks up the tree to find the first ancestor of a specific kind.
     *
     * @param kind The [SyntaxKind] to find
     * @return The first ancestor with that kind, or null if not found
     */
    fun findAncestorByKind(kind: SyntaxKind): SyntaxNode? {
        return findAncestor { it.kind == kind }
    }

    /**
     * Gets the sequence of ancestors from this node to the root.
     */
    val ancestors: Sequence<SyntaxNode>
        get() = sequence {
            var current = parent
            while (current != null) {
                yield(current)
                current = current.parent
            }
        }

    /**
     * Traverses this node and all descendants depth-first.
     *
     * @return Sequence of all nodes in depth-first order
     */
    fun traverse(): Sequence<SyntaxNode> = sequence {
        yield(this@SyntaxNode)
        for (child in children) {
            yieldAll(child.traverse())
        }
    }

    /**
     * Traverses only named nodes depth-first.
     *
     * @return Sequence of named nodes in depth-first order
     */
    fun traverseNamed(): Sequence<SyntaxNode> = sequence {
        if (isNamed) yield(this@SyntaxNode)
        for (child in namedChildren) {
            yieldAll(child.traverseNamed())
        }
    }

    /**
     * Returns the S-expression representation of this subtree.
     *
     * S-expressions are a compact textual representation of the tree structure,
     * useful for debugging and testing.
     */
    fun toSexp(): String = try { node.nodeString } catch (e: IllegalStateException) { "(error)" }

    /**
     * Checks if this node's position contains another position.
     */
    operator fun contains(position: Position): Boolean = position in range

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SyntaxNode) return false
        return node == other.node
    }

    override fun hashCode(): Int = node.hashCode()

    override fun toString(): String {
        return "SyntaxNode(kind=$kind, range=$range, text='${text.take(50)}${if (text.length > 50) "..." else ""}')"
    }
}
