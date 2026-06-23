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
            val maxLines = InlineSuggestionPreferences.maxLines
            val linesToDraw = suggestion.text.split("\n").take(maxLines)

            // Anchor to the LIVE caret rather than the position captured when the request started.
            // The suggestion is dismissed whenever the cursor moves, so the caret is always where
            // this suggestion belongs; reading it live keeps the ghost text on the cursor's row even
            // after the document is restructured (e.g. undo/redo), which a stale captured line could
            // get wrong. Coerce into range so a transient state can't throw.
            val cursor = editor.cursor
            val cursorLine = cursor.leftLine.coerceIn(0, editor.text.lineCount - 1)
            val cursorColumn = cursor.leftColumn.coerceIn(0, editor.text.getColumnCount(cursorLine))

            // Position using the editor's own layout, in view coordinates. getCharOffsetX accounts
            // for the line-number gutter, real glyph widths, and horizontal scroll. For Y we resolve
            // the (line, column) to its VISUAL ROW: getRowBaseline takes a row index, not a document
            // line, and the two diverge (e.g. with word wrap).
            val rowHeight = editor.rowHeight
            val charIndex = editor.text.getCharIndex(cursorLine, cursorColumn)
            val row = editor.layout.getRowIndexForPosition(charIndex)
            val firstBaseline = editor.getRowBaseline(row).toFloat() - editor.offsetY
            val firstX = editor.getCharOffsetX(cursorLine, cursorColumn)
            // Wrapped/continuation lines start at the left edge of the text region.
            val leftMargin = editor.measureTextRegionOffset() - editor.offsetX

            linesToDraw.forEachIndexed { index, line ->
                if (line.isNotEmpty()) {
                    val drawX = if (index == 0) firstX else leftMargin
                    val drawY = firstBaseline + index * rowHeight

                    // Only draw if visible in viewport
                    if (drawY > 0 && drawY < canvas.height + rowHeight) {
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
