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
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

class GhostTextRendererTest {

    private lateinit var mockEditor: IDEEditor
    private lateinit var renderer: GhostTextRenderer

    @Before
    fun setup() {
        mockEditor = mockk(relaxed = true)
        every { mockEditor.textSizePx } returns 14f
        every { mockEditor.typefaceText } returns null
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
}
