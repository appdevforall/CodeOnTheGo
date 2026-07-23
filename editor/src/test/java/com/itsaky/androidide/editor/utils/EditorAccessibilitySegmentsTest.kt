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
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [EditorAccessibilitySegments], which drives screen-reader cursor
 * synchronization. The returned ranges are absolute char indices into the flat text.
 */
@RunWith(RobolectricTestRunner::class)
class EditorAccessibilitySegmentsTest {

    private val char = AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER
    private val word = AccessibilityNodeInfo.MOVEMENT_GRANULARITY_WORD
    private val line = AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE
    private val paragraph = AccessibilityNodeInfo.MOVEMENT_GRANULARITY_PARAGRAPH

    private fun following(text: String, granularity: Int, offset: Int) =
        EditorAccessibilitySegments.following(text, granularity, offset)?.toList()

    private fun preceding(text: String, granularity: Int, offset: Int) =
        EditorAccessibilitySegments.preceding(text, granularity, offset)?.toList()

    // ---------------------------------------------------------------------------------------------
    // Character
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `character following returns the next single character`() {
        assertThat(following("abc", char, 0)).isEqualTo(listOf(0, 1))
        assertThat(following("abc", char, 1)).isEqualTo(listOf(1, 2))
    }

    @Test
    fun `character following at end returns null`() {
        assertThat(following("abc", char, 3)).isNull()
    }

    @Test
    fun `character preceding returns the previous single character`() {
        assertThat(preceding("abc", char, 3)).isEqualTo(listOf(2, 3))
        assertThat(preceding("abc", char, 1)).isEqualTo(listOf(0, 1))
    }

    @Test
    fun `character preceding at start returns null`() {
        assertThat(preceding("abc", char, 0)).isNull()
    }

    // ---------------------------------------------------------------------------------------------
    // Word
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `word following skips whitespace and returns whole words`() {
        val text = "foo bar"
        assertThat(following(text, word, 0)).isEqualTo(listOf(0, 3)) // foo
        assertThat(following(text, word, 3)).isEqualTo(listOf(4, 7)) // bar
    }

    @Test
    fun `word following past last word returns null`() {
        assertThat(following("foo bar", word, 7)).isNull()
    }

    @Test
    fun `word following from inside a word snaps to the whole enclosing word`() {
        // Offset 2 is inside "hello"; the whole word must be traversed, not a truncated tail.
        assertThat(following("hello", word, 2)).isEqualTo(listOf(0, 5))
    }

    @Test
    fun `word preceding from inside a word snaps to the whole enclosing word`() {
        assertThat(preceding("hello", word, 2)).isEqualTo(listOf(0, 5))
    }

    @Test
    fun `word preceding returns previous word`() {
        val text = "foo bar"
        assertThat(preceding(text, word, 7)).isEqualTo(listOf(4, 7)) // bar
        assertThat(preceding(text, word, 4)).isEqualTo(listOf(0, 3)) // foo
    }

    @Test
    fun `word navigation skips separator punctuation between identifiers`() {
        // Simulates code like "x=y" — the identifiers are words, '=' is skipped.
        val text = "x=y"
        assertThat(following(text, word, 0)).isEqualTo(listOf(0, 1)) // x
        assertThat(following(text, word, 1)).isEqualTo(listOf(2, 3)) // y
    }

    @Test
    fun `word navigation treats a dotted identifier as a single word`() {
        // Matches EditText/BreakIterator semantics: "a.b" is one word, not three.
        val text = "a.b"
        assertThat(following(text, word, 0)).isEqualTo(listOf(0, 3))
    }

    // ---------------------------------------------------------------------------------------------
    // Line
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `line following returns the current line when at its start`() {
        val text = "abc\ndef\nghi"
        assertThat(following(text, line, 0)).isEqualTo(listOf(0, 3)) // abc
    }

    @Test
    fun `line following from mid-line advances to the next line`() {
        val text = "abc\ndef\nghi"
        // offset 5 is inside "def"; next line is "ghi".
        assertThat(following(text, line, 5)).isEqualTo(listOf(8, 11))
    }

    @Test
    fun `line following from end of a line moves to the next line`() {
        val text = "abc\ndef"
        // offset 3 is the '\n' after abc -> caret sits at end of line 0.
        assertThat(following(text, line, 3)).isEqualTo(listOf(4, 7)) // def
    }

    @Test
    fun `line preceding returns the line the caret sits at the end of`() {
        val text = "abc\ndef"
        assertThat(preceding(text, line, 7)).isEqualTo(listOf(4, 7)) // def
    }

    @Test
    fun `line preceding from a line start moves to the previous line`() {
        val text = "abc\ndef"
        // offset 4 is the start of "def"; previous line is "abc".
        assertThat(preceding(text, line, 4)).isEqualTo(listOf(0, 3))
    }

    @Test
    fun `line handles empty lines`() {
        val text = "abc\n\ndef"
        // index 4 is the empty line between the two newlines.
        assertThat(following(text, line, 4)).isEqualTo(listOf(4, 4))
    }

    // ---------------------------------------------------------------------------------------------
    // Paragraph
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `paragraph following returns the current non-empty line`() {
        val text = "abc\ndef\nghi"
        assertThat(following(text, paragraph, 0)).isEqualTo(listOf(0, 3)) // abc
    }

    @Test
    fun `paragraph following skips blank lines`() {
        val text = "abc\n\ndef"
        // From end of "abc" (index 3), the next paragraph is "def" — the blank line is skipped.
        assertThat(following(text, paragraph, 3)).isEqualTo(listOf(5, 8))
    }

    @Test
    fun `paragraph preceding skips blank lines`() {
        val text = "abc\n\ndef"
        // From the start of "def" (index 5), the previous paragraph is "abc".
        assertThat(preceding(text, paragraph, 5)).isEqualTo(listOf(0, 3))
    }

    @Test
    fun `paragraph preceding returns the line the caret sits at the end of`() {
        val text = "abc\ndef"
        assertThat(preceding(text, paragraph, 7)).isEqualTo(listOf(4, 7)) // def
    }

    @Test
    fun `paragraph following from a mid-paragraph offset advances to the next paragraph`() {
        val text = "abc\ndef\nghi"
        // offset 5 is inside "def"; next paragraph is "ghi".
        assertThat(following(text, paragraph, 5)).isEqualTo(listOf(8, 11))
    }

    @Test
    fun `paragraph preceding from a mid-paragraph offset returns the whole enclosing paragraph`() {
        val text = "abc\ndef\nghi"
        // offset 5 is inside "def"; the whole line must be returned, not a truncated head.
        assertThat(preceding(text, paragraph, 5)).isEqualTo(listOf(4, 7))
    }

    // ---------------------------------------------------------------------------------------------
    // Guards
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `empty text yields no segments`() {
        assertThat(following("", char, 0)).isNull()
        assertThat(preceding("", word, 0)).isNull()
        assertThat(following("", line, 0)).isNull()
    }

    @Test
    fun `unsupported granularity yields null`() {
        assertThat(following("abc", 999, 0)).isNull()
        assertThat(preceding("abc", 999, 3)).isNull()
    }

    @Test
    fun `supported granularities bitmask includes character word and line`() {
        val mask = EditorAccessibilitySegments.SUPPORTED_GRANULARITIES
        assertThat(mask and char).isEqualTo(char)
        assertThat(mask and word).isEqualTo(word)
        assertThat(mask and line).isEqualTo(line)
        assertThat(mask and paragraph).isEqualTo(paragraph)
    }
}
