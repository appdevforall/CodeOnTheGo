package org.appdevforall.codeonthego.lsp.kotlin.symbol

import org.appdevforall.codeonthego.lsp.kotlin.parser.Position
import org.appdevforall.codeonthego.lsp.kotlin.parser.TextRange

/**
 * Location of a symbol declaration in source code.
 *
 * This class captures where a symbol is defined, including:
 * - The file path containing the declaration
 * - The text range covering the entire declaration
 * - The text range covering just the symbol's name (for go-to-definition)
 *
 * ## Usage
 *
 * ```kotlin
 * val location = SymbolLocation(
 *     filePath = "/src/main/kotlin/Foo.kt",
 *     range = TextRange(Position(0, 0), Position(5, 1)),
 *     nameRange = TextRange(Position(0, 6), Position(0, 9))
 * )
 *
 * // For "class Foo { ... }"
 * // range covers "class Foo { ... }"
 * // nameRange covers "Foo"
 * ```
 *
 * ## Synthetic Symbols
 *
 * For symbols that don't have a source location (e.g., stdlib symbols loaded from index),
 * use [SYNTHETIC] which has empty ranges.
 *
 * @property filePath Absolute path to the source file
 * @property range Text range covering the entire declaration
 * @property nameRange Text range covering the symbol's name (for precise navigation)
 */
data class SymbolLocation(
    val filePath: String,
    val range: TextRange,
    val nameRange: TextRange
) {
    /**
     * The start position of the declaration.
     */
    val startPosition: Position get() = range.start

    /**
     * The end position of the declaration.
     */
    val endPosition: Position get() = range.end

    /**
     * The position of the symbol's name (for go-to-definition).
     */
    val namePosition: Position get() = nameRange.start

    /**
     * Whether this location is from actual source code.
     */
    val isFromSource: Boolean get() = filePath.isNotEmpty()

    /**
     * Whether this is a synthetic location (no source).
     */
    val isSynthetic: Boolean get() = !isFromSource

    /**
     * Returns a display string for error messages and hover info.
     */
    fun toDisplayString(): String {
        return if (isFromSource) {
            "$filePath:${range.start.displayLine}:${range.start.displayColumn}"
        } else {
            "<synthetic>"
        }
    }

    override fun toString(): String = toDisplayString()

    companion object {
        /**
         * Location for symbols without source (stdlib, synthetic).
         */
        val SYNTHETIC: SymbolLocation = SymbolLocation(
            filePath = "",
            range = TextRange.EMPTY,
            nameRange = TextRange.EMPTY
        )

        /**
         * Creates a location from a file path and position.
         *
         * @param filePath Path to the source file
         * @param range Range covering the declaration
         * @param nameRange Range covering just the name
         */
        fun of(
            filePath: String,
            range: TextRange,
            nameRange: TextRange = range
        ): SymbolLocation = SymbolLocation(filePath, range, nameRange)

        /**
         * Creates a location covering a single position (point).
         */
        fun at(filePath: String, position: Position): SymbolLocation {
            val range = TextRange(position, position)
            return SymbolLocation(filePath, range, range)
        }
    }
}
