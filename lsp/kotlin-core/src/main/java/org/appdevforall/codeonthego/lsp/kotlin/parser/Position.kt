package org.appdevforall.codeonthego.lsp.kotlin.parser

/**
 * Represents a position within source code using line and column numbers.
 *
 * ## Coordinate System
 *
 * This class uses **zero-based** line and column numbers internally (matching tree-sitter),
 * but provides conversion methods for **one-based** coordinates used in editors and error messages.
 *
 * ```
 * Line 0: fun main() {
 *         ^   ^
 *         |   column 4
 *         column 0
 *
 * Line 1:     println("Hello")
 * ```
 *
 * ## Immutability
 *
 * Position instances are immutable. Operations that modify position return new instances.
 *
 * ## Usage
 *
 * ```kotlin
 * val pos = Position(line = 0, column = 5)
 * val display = "Error at line ${pos.displayLine}, column ${pos.displayColumn}"
 * ```
 *
 * @property line Zero-based line number
 * @property column Zero-based column number (byte offset within line)
 */
data class Position(
    val line: Int,
    val column: Int
) : Comparable<Position> {

    init {
        require(line >= 0) { "Line number must be non-negative, got $line" }
        require(column >= 0) { "Column number must be non-negative, got $column" }
    }

    /**
     * One-based line number for display in editors and error messages.
     * Line 0 becomes line 1, etc.
     */
    val displayLine: Int get() = line + 1

    /**
     * One-based column number for display in editors and error messages.
     * Column 0 becomes column 1, etc.
     */
    val displayColumn: Int get() = column + 1

    /**
     * Compares positions lexicographically (line first, then column).
     *
     * @param other The position to compare with
     * @return Negative if this comes before other, positive if after, zero if equal
     */
    override fun compareTo(other: Position): Int {
        val lineCompare = line.compareTo(other.line)
        return if (lineCompare != 0) lineCompare else column.compareTo(other.column)
    }

    /**
     * Creates a new position offset by the given line and column deltas.
     *
     * @param lineDelta Number of lines to add (can be negative)
     * @param columnDelta Number of columns to add (can be negative)
     * @return New position with applied offsets
     * @throws IllegalArgumentException if resulting position would have negative coordinates
     */
    fun offset(lineDelta: Int, columnDelta: Int): Position {
        return Position(
            line = line + lineDelta,
            column = column + columnDelta
        )
    }

    /**
     * Returns a human-readable string for display purposes.
     * Uses one-based line and column numbers.
     */
    override fun toString(): String = "$displayLine:$displayColumn"

    companion object {
        /** Position at the start of a document */
        val ZERO: Position = Position(0, 0)

        /**
         * Creates a position from one-based (display) coordinates.
         *
         * @param displayLine One-based line number (as shown in editors)
         * @param displayColumn One-based column number
         * @return Position with zero-based coordinates
         */
        fun fromDisplay(displayLine: Int, displayColumn: Int): Position {
            require(displayLine >= 1) { "Display line must be >= 1, got $displayLine" }
            require(displayColumn >= 1) { "Display column must be >= 1, got $displayColumn" }
            return Position(displayLine - 1, displayColumn - 1)
        }
    }
}
