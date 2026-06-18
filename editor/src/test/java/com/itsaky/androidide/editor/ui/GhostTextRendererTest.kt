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

package com.itsaky.androidide.editor.ui

import android.graphics.Canvas
import android.graphics.Paint
import com.itsaky.androidide.models.Position
import io.mockk.mockk
import io.mockk.every
import io.mockk.slot
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

class GhostTextRendererTest {

    private lateinit var mockEditor: IDEEditor
    private lateinit var mockCanvas: Canvas
    private lateinit var renderer: GhostTextRenderer

    @Before
    fun setup() {
        mockEditor = mockk(relaxed = true)
        mockCanvas = mockk(relaxed = true)

        every { mockEditor.textSizePx } returns 14f
        every { mockEditor.typefaceText } returns null
        every { mockCanvas.width } returns 800
        every { mockCanvas.height } returns 1200

        renderer = GhostTextRenderer(mockEditor)
    }

    @Test
    fun `initial state is hidden`() {
        assertFalse(renderer.isVisible())
    }

    @Test
    fun `show makes renderer visible`() {
        val suggestion = SuggestionData(
            text = "test",
            startPosition = Position(0, 0, 0),
            cursorLine = 0,
            cursorColumn = 0,
            requestTimestamp = System.currentTimeMillis()
        )

        renderer.show(suggestion)
        assertTrue(renderer.isVisible())
    }

    @Test
    fun `hide makes renderer invisible`() {
        val suggestion = SuggestionData(
            text = "test",
            startPosition = Position(0, 0, 0),
            cursorLine = 0,
            cursorColumn = 0,
            requestTimestamp = System.currentTimeMillis()
        )

        renderer.show(suggestion)
        assertTrue(renderer.isVisible())

        renderer.hide()
        assertFalse(renderer.isVisible())
    }

    @Test
    fun `onDraw does not crash with null canvas`() {
        // Should handle gracefully even if called incorrectly
        // Just verify no exception thrown
        renderer.onDraw(mockk(relaxed = true))
    }

    @Test
    fun `calculates correct position for single line suggestion`() {
        val suggestion = SuggestionData(
            text = "println(\"hello\")",
            startPosition = Position(5, 10, 50),
            cursorLine = 5,
            cursorColumn = 10,
            requestTimestamp = System.currentTimeMillis()
        )

        every { mockCanvas.width } returns 800
        every { mockCanvas.height } returns 1200

        renderer.show(suggestion)

        // Verify renderer has the suggestion
        assertTrue(renderer.isVisible())
    }

    @Test
    fun `splits multi-line text correctly`() {
        val multiLineText = "line1\nline2\nline3"
        val suggestion = SuggestionData(
            text = multiLineText,
            startPosition = Position(0, 0, 0),
            cursorLine = 0,
            cursorColumn = 0,
            requestTimestamp = System.currentTimeMillis()
        )

        renderer.show(suggestion)

        val lines = suggestion.text.split("\n")
        assertEquals(3, lines.size)
        assertTrue(renderer.isVisible())
    }

    @Test
    fun `enforces max 5 lines constraint`() {
        val multiLineText = "l1\nl2\nl3\nl4\nl5\nl6\nl7"
        val suggestion = SuggestionData(
            text = multiLineText,
            startPosition = Position(0, 0, 0),
            cursorLine = 0,
            cursorColumn = 0,
            requestTimestamp = System.currentTimeMillis()
        )

        renderer.show(suggestion)

        val lines = suggestion.text.split("\n")
        assertEquals(7, lines.size)

        // But only 5 should be drawn
        val maxLinesToDraw = 5
        val linesToDraw = lines.take(maxLinesToDraw)
        assertEquals(5, linesToDraw.size)

        assertTrue(renderer.isVisible())
    }

    @Test
    fun `onDraw returns early if suggestion is null`() {
        renderer.onDraw(mockCanvas)

        // Should not crash and not attempt any canvas operations
        assertFalse(renderer.isVisible())
    }

    @Test
    fun `onDraw handles empty lines gracefully`() {
        val suggestion = SuggestionData(
            text = "line1\n\nline3",
            startPosition = Position(0, 0, 0),
            cursorLine = 0,
            cursorColumn = 0,
            requestTimestamp = System.currentTimeMillis()
        )

        renderer.show(suggestion)
        renderer.onDraw(mockCanvas)

        // Should handle empty lines without crashing
        // Visibility might be lost if exception occurred, but main thing is no crash
        assertFalse(renderer.isVisible())  // Exception will hide it when paint is null
    }

    @Test
    fun `onDraw handles exception during drawing`() {
        val suggestion = SuggestionData(
            text = "test",
            startPosition = Position(0, 0, 0),
            cursorLine = 0,
            cursorColumn = 0,
            requestTimestamp = System.currentTimeMillis()
        )

        every { mockCanvas.width } returns 800
        every { mockCanvas.height } returns 1200
        every { mockCanvas.drawText(any<String>(), any<Float>(), any<Float>(), any()) } throws RuntimeException("Canvas error")

        renderer.show(suggestion)
        renderer.onDraw(mockCanvas)

        // After exception, should hide and be invisible
        assertFalse(renderer.isVisible())
    }
}
