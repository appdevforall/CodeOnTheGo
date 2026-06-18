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
import com.itsaky.androidide.preferences.internal.InlineSuggestionPreferences
import org.slf4j.LoggerFactory

/**
 * Renders inline suggestion text as semi-transparent "ghost" text in the editor.
 *
 * Ghost text appears at the cursor position and uses the editor's current font
 * but with reduced opacity to distinguish it from real code.
 */
class GhostTextRenderer(private val editor: IDEEditor) {

    private val log = LoggerFactory.getLogger(GhostTextRenderer::class.java)

    private var currentSuggestion: SuggestionData? = null

    private var ghostPaint: Paint? = null

    companion object {
        // Ghost text color: 40% opacity gray (ARGB: 102, 128, 128, 128)
        // Stored as raw int to avoid Color.argb() call during class initialization in unit tests
        private const val GHOST_TEXT_COLOR = 0x66808080  // ARGB format: 66=102 alpha, 80=128 for RGB
    }

    private fun getGhostPaint(): Paint {
        return ghostPaint ?: Paint().apply {
            color = GHOST_TEXT_COLOR
            isAntiAlias = true
        }.also { ghostPaint = it }
    }

    /**
     * Returns true if a suggestion is currently visible.
     */
    fun isVisible(): Boolean {
        return currentSuggestion != null
    }

    /**
     * Show the given suggestion as ghost text.
     */
    fun show(suggestion: SuggestionData) {
        currentSuggestion = suggestion
        // Paint properties are updated lazily in onDraw to avoid Android framework calls in tests
    }

    /**
     * Hide the current suggestion.
     */
    fun hide() {
        currentSuggestion = null
    }

    /**
     * Draw the ghost text on the canvas. Called by InlineSuggestionComponent.
     */
    fun onDraw(canvas: Canvas) {
        val suggestion = currentSuggestion ?: return

        try {
            updatePaintProperties()

            val paint = ghostPaint ?: return
            val lines = suggestion.text.split("\n")
            val maxLines = InlineSuggestionPreferences.maxLines
            val linesToDraw = lines.take(maxLines)

            // Get text metrics
            val lineHeight = paint.fontSpacing
            val charWidth = paint.measureText("M")

            // Calculate cursor screen position
            val cursorLine = suggestion.cursorLine
            val cursorColumn = suggestion.cursorColumn

            // Base position - starting from cursor
            // Y position: approximate based on line height
            // X position: approximate based on character width
            val baseY = (cursorLine + 1) * lineHeight
            val baseX = cursorColumn * charWidth

            // Draw each line of the suggestion
            linesToDraw.forEachIndexed { index, line ->
                if (line.isNotEmpty()) {
                    val drawX = if (index == 0) baseX else 0f
                    val drawY = baseY + (index * lineHeight)

                    // Only draw if visible in viewport
                    if (drawY > 0 && drawY < canvas.height) {
                        canvas.drawText(line, drawX, drawY, paint)
                    }
                }
            }

            log.trace("Drew ghost text: ${linesToDraw.size} lines")
        } catch (e: Exception) {
            log.error("Error drawing ghost text", e)
            hide()
        }
    }

    private fun updatePaintProperties() {
        val paint = getGhostPaint()
        paint.textSize = editor.textSizePx
        paint.typeface = editor.typefaceText
    }
}
