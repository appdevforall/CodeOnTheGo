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
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.EditorRenderer

/**
 * An [EditorRenderer] that draws a single-line inline "ghost text" suggestion (dimmed grey text)
 * at a fixed anchor position, after the editor's normal content. This backs the host side of the
 * inline-suggestion plugin pipeline (`IdeEditorService.showInlineSuggestion`).
 *
 * State is mutated from the main thread (via [IDEEditor]); [draw] reads it defensively and must
 * never throw — a bad suggestion or transient layout state can never take the editor's draw pass
 * down with it.
 */
class GhostTextRenderer(private val editor: CodeEditor) : EditorRenderer(editor) {

  @Volatile
  private var suggestion: String? = null

  @Volatile
  private var anchorLine: Int = -1

  @Volatile
  private var anchorColumn: Int = -1

  private val ghostPaint = Paint(Paint.ANTI_ALIAS_FLAG)

  val hasSuggestion: Boolean
    get() = suggestion != null

  /** Sets the pending suggestion anchored at the given 0-indexed [line]/[column]. */
  fun setSuggestion(text: String, line: Int, column: Int) {
    suggestion = text.takeIf { it.isNotEmpty() }
    anchorLine = line
    anchorColumn = column
  }

  /** Clears any pending suggestion and returns the text that was showing (or null). */
  fun takeSuggestion(): String? {
    val current = suggestion
    clearSuggestion()
    return current
  }

  fun clearSuggestion() {
    suggestion = null
    anchorLine = -1
    anchorColumn = -1
  }

  override fun draw(canvas: Canvas) {
    super.draw(canvas)

    val text = suggestion ?: return
    val line = anchorLine
    val column = anchorColumn
    try {
      val content = editor.text
      if (line < 0 || line >= content.lineCount) return
      if (column < 0 || column > content.getColumnCount(line)) return

      val basePaint = editor.textPaint
      ghostPaint.textSize = basePaint.textSize
      ghostPaint.typeface = basePaint.typeface
      // Mid-grey at ~50% alpha reads as "ghost" on both light and dark themes.
      ghostPaint.color = GHOST_COLOR

      val x = editor.getCharOffsetX(line, column) + editor.offsetX
      // getRowBaseline is the exact baseline sora draws its own text at (content space).
      val baseline = editor.getRowBaseline(line).toFloat()
      // Ghost text is single-line: only draw up to the first newline.
      canvas.drawText(text.substringBefore('\n'), x, baseline, ghostPaint)
    } catch (_: Throwable) {
      // Never let suggestion rendering crash the editor.
    }
  }

  private companion object {
    private const val GHOST_COLOR = 0x80888888.toInt()
  }
}
