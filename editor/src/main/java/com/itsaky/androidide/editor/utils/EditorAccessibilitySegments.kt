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

package com.itsaky.androidide.editor.utils

import android.view.accessibility.AccessibilityNodeInfo
import java.text.BreakIterator

/**
 * Pure text-segment iteration used to keep the editor's text cursor in sync with a
 * screen reader's "move by granularity" navigation, so the editor behaves like a
 * standard [android.widget.EditText] under TalkBack.
 *
 * The editor exposes its whole content to accessibility services as a single flat
 * [CharSequence]. Movement granularity actions therefore operate on absolute character
 * indices into that flat text; the caller is responsible for mapping the returned
 * indices back to (line, column) positions in the editor.
 *
 * The character and word implementations mirror the semantics of the framework's
 * (hidden) `android.view.AccessibilityIterators`, so navigation matches what users
 * already expect from an [android.widget.EditText]. Line navigation walks logical
 * lines (separated by `'\n'`), which for source code is the natural unit.
 *
 * @author Code On The Go
 */
object EditorAccessibilitySegments {

    private const val DONE = BreakIterator.DONE

    /**
     * The set of movement granularities that [following] and [preceding] can handle.
     * Intended to be advertised via [AccessibilityNodeInfo.setMovementGranularities].
     */
    const val SUPPORTED_GRANULARITIES =
        AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER or
            AccessibilityNodeInfo.MOVEMENT_GRANULARITY_WORD or
            AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE

    /**
     * Returns the `[start, end]` range (end-exclusive) of the segment that follows
     * [offset] for the given [granularity], or `null` when there is nothing after it.
     */
    fun following(
        text: CharSequence,
        granularity: Int,
        offset: Int,
    ): IntArray? =
        when (granularity) {
            AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER -> characterFollowing(text, offset)
            AccessibilityNodeInfo.MOVEMENT_GRANULARITY_WORD -> wordFollowing(text, offset)
            AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE -> lineFollowing(text, offset)
            else -> null
        }

    /**
     * Returns the `[start, end]` range (end-exclusive) of the segment that precedes
     * [offset] for the given [granularity], or `null` when there is nothing before it.
     */
    fun preceding(
        text: CharSequence,
        granularity: Int,
        offset: Int,
    ): IntArray? =
        when (granularity) {
            AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER -> characterPreceding(text, offset)
            AccessibilityNodeInfo.MOVEMENT_GRANULARITY_WORD -> wordPreceding(text, offset)
            AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE -> linePreceding(text, offset)
            else -> null
        }

    // ---------------------------------------------------------------------------------------------
    // Character
    // ---------------------------------------------------------------------------------------------

    private fun characterFollowing(text: CharSequence, offset: Int): IntArray? {
        val length = text.length
        if (length <= 0 || offset >= length) return null
        val iterator = BreakIterator.getCharacterInstance().apply { setText(text.toString()) }
        var start = if (offset < 0) 0 else offset
        if (!iterator.isBoundary(start)) {
            start = iterator.following(start)
            if (start == DONE) return null
        }
        val end = iterator.following(start)
        if (end == DONE) return null
        return intArrayOf(start, end)
    }

    private fun characterPreceding(text: CharSequence, offset: Int): IntArray? {
        val length = text.length
        if (length <= 0 || offset <= 0) return null
        val iterator = BreakIterator.getCharacterInstance().apply { setText(text.toString()) }
        var end = if (offset > length) length else offset
        if (!iterator.isBoundary(end)) {
            end = iterator.preceding(end)
            if (end == DONE) return null
        }
        val start = iterator.preceding(end)
        if (start == DONE) return null
        return intArrayOf(start, end)
    }

    // ---------------------------------------------------------------------------------------------
    // Word
    // ---------------------------------------------------------------------------------------------

    private fun wordFollowing(text: CharSequence, offset: Int): IntArray? {
        val length = text.length
        if (length <= 0 || offset >= length) return null
        val iterator = BreakIterator.getWordInstance().apply { setText(text.toString()) }
        var start = if (offset < 0) 0 else offset
        // Skip past any non-word characters (whitespace, punctuation) to the start of a word.
        while (!isLetterOrDigit(text, start) && !isWordStart(text, start)) {
            start = iterator.following(start)
            if (start == DONE) return null
        }
        val end = iterator.following(start)
        if (end == DONE || !isWordEnd(text, end)) return null
        return intArrayOf(start, end)
    }

    private fun wordPreceding(text: CharSequence, offset: Int): IntArray? {
        val length = text.length
        if (length <= 0 || offset <= 0) return null
        val iterator = BreakIterator.getWordInstance().apply { setText(text.toString()) }
        var end = if (offset > length) length else offset
        while (!isLetterOrDigit(text, end - 1) && !isWordEnd(text, end)) {
            end = iterator.preceding(end)
            if (end == DONE) return null
        }
        val start = iterator.preceding(end)
        if (start == DONE || !isWordStart(text, start)) return null
        return intArrayOf(start, end)
    }

    private fun isWordStart(text: CharSequence, index: Int): Boolean =
        isLetterOrDigit(text, index) && (index == 0 || !isLetterOrDigit(text, index - 1))

    private fun isWordEnd(text: CharSequence, index: Int): Boolean =
        (index > 0 && isLetterOrDigit(text, index - 1)) &&
            (index == text.length || !isLetterOrDigit(text, index))

    private fun isLetterOrDigit(text: CharSequence, index: Int): Boolean =
        index in 0 until text.length && Character.isLetterOrDigit(text[index])

    // ---------------------------------------------------------------------------------------------
    // Line (logical, '\n'-separated)
    // ---------------------------------------------------------------------------------------------

    private fun lineFollowing(text: CharSequence, offset: Int): IntArray? {
        val length = text.length
        if (length <= 0 || offset >= length) return null
        var start = if (offset < 0) 0 else offset
        // When not already at a line start, advance to the beginning of the next line.
        if (start > 0 && text[start - 1] != '\n') {
            while (start < length && text[start] != '\n') start++
            if (start < length) start++ // step over the newline onto the next line
            if (start >= length) return null
        }
        var end = start
        while (end < length && text[end] != '\n') end++
        return intArrayOf(start, end)
    }

    private fun linePreceding(text: CharSequence, offset: Int): IntArray? {
        val length = text.length
        if (length <= 0 || offset <= 0) return null
        var end = if (offset > length) length else offset
        // If sitting on a line start, step back onto the previous line's newline.
        if (text[end - 1] == '\n') end--
        if (end <= 0) return null
        var start = end
        while (start > 0 && text[start - 1] != '\n') start--
        var lineEnd = start
        while (lineEnd < length && text[lineEnd] != '\n') lineEnd++
        return intArrayOf(start, lineEnd)
    }
}
