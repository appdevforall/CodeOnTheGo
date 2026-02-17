package org.appdevforall.codeonthego.lsp.kotlin.parser

/**
 * Represents a range of text in source code, defined by start and end positions.
 *
 * ## Range Semantics
 *
 * Ranges are **inclusive** at the start and **exclusive** at the end (half-open interval).
 * This matches standard programming conventions and LSP specification.
 *
 * ```
 * Source: fun main()
 * Range:  ^^^
 * start:  (0, 0)
 * end:    (0, 3)  -- points AFTER 'n', so "fun" has length 3
 * ```
 *
 * ## Byte vs Character Positions
 *
 * Column numbers represent **byte offsets**, not character offsets. For ASCII text,
 * these are identical. For UTF-8 text with multi-byte characters, byte offsets may
 * be larger than character counts.
 *
 * ## Immutability
 *
 * TextRange instances are immutable. All operations return new instances.
 *
 * ## Usage
 *
 * ```kotlin
 * val range = TextRange(
 *     start = Position(0, 4),
 *     end = Position(0, 8)
 * )
 * println(range.contains(Position(0, 5))) // true
 * ```
 *
 * @property start The starting position (inclusive)
 * @property end The ending position (exclusive)
 */
data class TextRange(
    val start: Position,
    val end: Position,
    val startOffset: Int = -1,
    val endOffset: Int = -1
) {
    init {
        require(start <= end) {
            "Start position ($start) must not be after end position ($end)"
        }
    }

    val hasOffsets: Boolean get() = startOffset >= 0 && endOffset >= 0

    /**
     * Zero-based start byte offset.
     * This is derived from the position; for actual byte offset, use [startByte].
     */
    val startLine: Int get() = start.line

    /** Zero-based end line */
    val endLine: Int get() = end.line

    /** Zero-based start column (byte offset within line) */
    val startColumn: Int get() = start.column

    /** Zero-based end column (byte offset within line) */
    val endColumn: Int get() = end.column

    /**
     * Number of lines this range spans.
     *
     * A single-line range returns 1.
     * A range from line 0 to line 2 returns 3.
     */
    val lineCount: Int get() = endLine - startLine + 1

    /**
     * Whether this range spans multiple lines.
     */
    val isMultiLine: Boolean get() = startLine != endLine

    /**
     * Whether this range is empty (start equals end).
     *
     * Empty ranges represent cursor positions or zero-width locations.
     */
    val isEmpty: Boolean get() = start == end

    /**
     * Approximate length of this range in terms of position.
     *
     * For single-line ranges, this is the column difference.
     * For multi-line ranges, this is a rough approximation.
     */
    val length: Int get() = if (isMultiLine) {
        (endLine - startLine) * 80 + endColumn
    } else {
        endColumn - startColumn
    }

    /**
     * Checks if the given position falls within this range.
     *
     * A position is contained if it is >= start and < end (half-open).
     *
     * @param position The position to check
     * @return true if position is within the range
     */
    operator fun contains(position: Position): Boolean {
        return position >= start && position < end
    }

    /**
     * Checks if another range is completely contained within this range.
     *
     * @param other The range to check
     * @return true if other is fully within this range
     */
    operator fun contains(other: TextRange): Boolean {
        return other.start >= start && other.end <= end
    }

    /**
     * Checks if this range overlaps with another range.
     *
     * Two ranges overlap if they share at least one position.
     * Adjacent ranges (one ends where other starts) do NOT overlap.
     *
     * @param other The range to check
     * @return true if ranges overlap
     */
    fun overlaps(other: TextRange): Boolean {
        return start < other.end && end > other.start
    }

    /**
     * Checks if this range is adjacent to another range.
     *
     * Ranges are adjacent if one ends exactly where the other begins.
     *
     * @param other The range to check
     * @return true if ranges are adjacent
     */
    fun adjacentTo(other: TextRange): Boolean {
        return end == other.start || start == other.end
    }

    /**
     * Returns the intersection of this range with another, or null if they don't overlap.
     *
     * @param other The range to intersect with
     * @return The overlapping portion, or null if no overlap
     */
    fun intersect(other: TextRange): TextRange? {
        val newStart = maxOf(start, other.start)
        val newEnd = minOf(end, other.end)
        return if (newStart < newEnd) TextRange(newStart, newEnd) else null
    }

    /**
     * Returns the smallest range that contains both this range and another.
     *
     * @param other The range to merge with
     * @return A range spanning both ranges
     */
    fun union(other: TextRange): TextRange {
        return TextRange(
            start = minOf(start, other.start),
            end = maxOf(end, other.end)
        )
    }

    /**
     * Extends this range to include the given position.
     *
     * @param position The position to include
     * @return A range that includes both the original range and the position
     */
    fun extendTo(position: Position): TextRange {
        return TextRange(
            start = minOf(start, position),
            end = maxOf(end, position)
        )
    }

    /**
     * Creates a new range with the start position offset.
     *
     * @param lineDelta Lines to add to start
     * @param columnDelta Columns to add to start
     * @return New range with offset start position
     */
    fun offsetStart(lineDelta: Int, columnDelta: Int): TextRange {
        return copy(start = start.offset(lineDelta, columnDelta))
    }

    /**
     * Creates a new range with the end position offset.
     *
     * @param lineDelta Lines to add to end
     * @param columnDelta Columns to add to end
     * @return New range with offset end position
     */
    fun offsetEnd(lineDelta: Int, columnDelta: Int): TextRange {
        return copy(end = end.offset(lineDelta, columnDelta))
    }

    /**
     * Returns a human-readable string representation.
     * Uses one-based line/column numbers for display.
     */
    override fun toString(): String {
        return if (isMultiLine || start != end) {
            "$start-$end"
        } else {
            start.toString()
        }
    }

    companion object {
        /** An empty range at the start of a document */
        val ZERO: TextRange = TextRange(Position.ZERO, Position.ZERO)

        /** Alias for ZERO - an empty range */
        val EMPTY: TextRange = ZERO

        /**
         * Creates a single-line range from column indices.
         *
         * @param line Zero-based line number
         * @param startColumn Zero-based start column
         * @param endColumn Zero-based end column
         * @return Range spanning the columns on the given line
         */
        fun onLine(line: Int, startColumn: Int, endColumn: Int): TextRange {
            return TextRange(
                start = Position(line, startColumn),
                end = Position(line, endColumn)
            )
        }

        /**
         * Creates a range representing a single position (zero-width).
         *
         * @param position The position
         * @return An empty range at the given position
         */
        fun at(position: Position): TextRange {
            return TextRange(position, position)
        }
    }
}
