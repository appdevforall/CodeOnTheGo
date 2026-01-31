package org.appdevforall.codeonthego.lsp.kotlin.parser

import com.itsaky.androidide.treesitter.TSInputEdit
import com.itsaky.androidide.treesitter.TSParser
import com.itsaky.androidide.treesitter.TSPoint
import com.itsaky.androidide.treesitter.TSTree
import com.itsaky.androidide.treesitter.TreeSitter
import com.itsaky.androidide.treesitter.kotlin.TSLanguageKotlin

/**
 * Parses Kotlin source code into syntax trees using tree-sitter.
 *
 * This parser provides:
 * - **Fast parsing**: Typical parse times are <10ms for most files
 * - **Incremental parsing**: Re-parses only changed portions after edits
 * - **Error tolerance**: Produces partial trees for syntactically invalid code
 * - **Thread safety**: Each instance is NOT thread-safe; create one per thread
 *
 * ## Architecture
 *
 * This class wraps the AndroidIDE tree-sitter-kotlin parser. Tree-sitter is a fast,
 * incremental parser generator that produces concrete syntax trees. The generated
 * trees are:
 * - Concrete (include all tokens, not just AST nodes)
 * - Error-tolerant (partial trees for malformed input)
 * - Incrementally updatable (efficient for editor use)
 *
 * ## Usage
 *
 * Basic parsing:
 * ```kotlin
 * val parser = KotlinParser()
 * val result = parser.parse("fun main() { println(\"Hello\") }")
 *
 * result.tree.use { tree ->
 *     val root = tree.root
 *     println(root.kind) // SOURCE_FILE
 * }
 * ```
 *
 * Incremental parsing:
 * ```kotlin
 * val parser = KotlinParser()
 * var result = parser.parse(initialSource)
 *
 * // After user edits...
 * val edit = SourceEdit(...)
 * result = parser.parseIncremental(newSource, result.tree, edit)
 * ```
 *
 * ## Thread Safety
 *
 * Parser instances are NOT thread-safe. For concurrent parsing:
 * - Create a parser instance per thread, or
 * - Use synchronization when accessing shared parser instance
 *
 * ## Resource Management
 *
 * The parser uses native resources. Call [close] when done or use `use` blocks.
 *
 * @see SyntaxTree The parsed tree structure
 * @see ParseResult Container for parse results with error information
 */
class KotlinParser : AutoCloseable {

    private val parser: TSParser

    init {
        TreeSitter.loadLibrary()
        parser = TSParser.create()
        parser.language = TSLanguageKotlin.getInstance()
    }

    /**
     * The tree-sitter language version.
     * Useful for debugging version mismatches.
     */
    val languageVersion: Int
        get() = 0

    /**
     * Parses Kotlin source code into a syntax tree.
     *
     * This method always succeeds, even for malformed input. Check
     * [ParseResult.hasErrors] to see if syntax errors were found.
     *
     * ## Performance
     *
     * Parsing is typically fast (<10ms for small files, <100ms for large files).
     * For repeated parsing of the same file with modifications, use
     * [parseIncremental] instead.
     *
     * @param source The complete Kotlin source code to parse
     * @param fileName Optional filename for error messages (not used for parsing)
     * @return [ParseResult] containing the tree and any syntax errors
     */
    fun parse(source: String, fileName: String = "<unknown>"): ParseResult {
        val startTime = System.currentTimeMillis()

        val tree = parser.parseString(source)
            ?: throw IllegalStateException("Parser returned null tree for source")

        val syntaxTree = SyntaxTree(tree, source, languageVersion)
        val errors = collectErrors(syntaxTree)
        val parseTime = System.currentTimeMillis() - startTime

        return ParseResult(
            tree = syntaxTree,
            syntaxErrors = errors,
            parseTimeMs = parseTime
        )
    }

    /**
     * Incrementally re-parses source code after an edit.
     *
     * Incremental parsing is much faster than full parsing for small edits.
     * Tree-sitter reuses unchanged portions of the previous tree.
     *
     * ## When to Use
     *
     * Use incremental parsing when:
     * - You have the previous tree from parsing the old source
     * - You know what edit was made (position and length)
     * - The edit is localized (not a complete rewrite)
     *
     * For large changes or when you don't have edit information,
     * use regular [parse] instead.
     *
     * @param source The complete new source code (after the edit)
     * @param previousTree The syntax tree from before the edit
     * @param edit Description of what changed in the source
     * @return [ParseResult] for the updated source
     */
    fun parseIncremental(
        source: String,
        previousTree: SyntaxTree,
        edit: SourceEdit
    ): ParseResult {
        val startTime = System.currentTimeMillis()

        val tsEdit = TSInputEdit.create(
            edit.startByte,
            edit.oldEndByte,
            edit.newEndByte,
            TSPoint.create(edit.startPosition.line, edit.startPosition.column),
            TSPoint.create(edit.oldEndPosition.line, edit.oldEndPosition.column),
            TSPoint.create(edit.newEndPosition.line, edit.newEndPosition.column)
        )

        previousTree.tree.edit(tsEdit)

        val newTree = parser.parseString(previousTree.tree, source)
            ?: throw IllegalStateException("Incremental parse returned null tree")

        val syntaxTree = SyntaxTree(newTree, source, languageVersion)
        val errors = collectErrors(syntaxTree)
        val parseTime = System.currentTimeMillis() - startTime

        return ParseResult(
            tree = syntaxTree,
            syntaxErrors = errors,
            parseTimeMs = parseTime
        )
    }

    /**
     * Sets the parsing timeout in microseconds.
     *
     * If parsing exceeds this timeout, it will be cancelled and return
     * a partial tree. Set to 0 (default) for no timeout.
     *
     * Use this for responsive UI when parsing very large files.
     *
     * @param timeoutMicros Timeout in microseconds, or 0 for no timeout
     */
    fun setTimeout(timeoutMicros: Long) {
        require(timeoutMicros >= 0) { "Timeout must be non-negative" }
        parser.timeout = timeoutMicros
    }

    /**
     * Gets the current parsing timeout in microseconds.
     *
     * @return Current timeout, or 0 if no timeout is set
     */
    fun getTimeout(): Long = parser.timeout

    /**
     * Resets the parser state.
     *
     * Call this if you want to ensure the parser starts fresh,
     * discarding any internal state from previous parses.
     */
    fun reset() {
        parser.reset()
    }

    /**
     * Releases native resources held by this parser.
     *
     * After calling close(), the parser should not be used.
     */
    override fun close() {
        parser.close()
    }

    /**
     * Collects all syntax errors from the parsed tree.
     *
     * Walks the tree looking for ERROR and MISSING nodes,
     * converting them to [SyntaxError] instances.
     */
    private fun collectErrors(tree: SyntaxTree): List<SyntaxError> {
        if (!tree.hasErrors) return emptyList()

        return tree.errors.map { errorNode ->
            SyntaxError.fromNode(errorNode)
        }
    }
}
