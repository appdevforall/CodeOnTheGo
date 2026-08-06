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
 * An [EditorRenderer] that draws an inline "ghost text" suggestion (dimmed grey text) at a fixed
 * anchor position, after the editor's normal content. Multi-line suggestions are previewed in full,
 * one row per line, so what is drawn matches what [IDEEditor] commits on accept. This backs the host
 * side of the inline-suggestion plugin pipeline (`IdeEditorService.showInlineSuggestion`).
 *
 * Extends [TracingEditorRenderer] so it inherits the block-line data-race guards (ADFA-2468) while
 * layering ghost text on top of the normal draw pass — the editor only supports a single renderer,
 * so the two behaviours have to share one class.
 *
 * State is mutated from the main thread (via [IDEEditor]); [draw] reads it defensively and must
 * never throw — a bad suggestion or transient layout state can never take the editor's draw pass
 * down with it.
 */
class GhostTextRenderer(private val editor: CodeEditor) : TracingEditorRenderer(editor = editor) {

  @Volatile
  private var suggestion: String? = null

  @Volatile
  private var anchorLine: Int = -1

  @Volatile
  private var anchorColumn: Int = -1

  /** Id of the plugin that owns the current suggestion, so dismissals can be scoped to it. */
  @Volatile
  private var ownerPluginId: String? = null

  private val ghostPaint = Paint(Paint.ANTI_ALIAS_FLAG)

  val hasSuggestion: Boolean
    get() = suggestion != null

  /**
   * Sets the pending suggestion anchored at the given 0-indexed [line]/[column], owned by
   * [pluginId]. A newer suggestion replaces any existing one (last writer wins) and takes ownership.
   */
  fun setSuggestion(text: String, line: Int, column: Int, pluginId: String) {
    suggestion = text.takeIf { it.isNotEmpty() }
    anchorLine = line
    anchorColumn = column
    ownerPluginId = pluginId
  }

  /** Clears any pending suggestion and returns the text that was showing (or null). */
  fun takeSuggestion(): String? {
    val current = suggestion
    clearSuggestion()
    return current
  }

  /**
   * Clears the suggestion only if it is owned by [pluginId], so one plugin can't dismiss another's
   * ghost text. Returns true if a suggestion was actually cleared.
   */
  fun clearSuggestionFor(pluginId: String): Boolean {
    if (suggestion == null || ownerPluginId != pluginId) return false
    clearSuggestion()
    return true
  }

  fun clearSuggestion() {
    suggestion = null
    anchorLine = -1
    anchorColumn = -1
    ownerPluginId = null
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

      // First line continues from the anchor column; continuation lines start at the content's
      // left edge (column 0 of the anchor line). offsetX applies the current horizontal scroll.
      val firstX = editor.getCharOffsetX(line, column) + editor.offsetX
      val leftX = editor.getCharOffsetX(line, 0) + editor.offsetX
      // getRowBaseline is the exact baseline sora draws its own text at (content space). Rows past
      // the last existing line have no baseline of their own, so extrapolate by row height.
      val anchorBaseline = editor.getRowBaseline(line).toFloat()
      val rowHeight = editor.rowHeight

      // Continuation lines overlay the rows below the anchor (the preview does not push content
      // down); drawing every line guarantees the preview matches the committed text exactly.
      text.split('\n').forEachIndexed { i, lineText ->
        val targetLine = line + i
        val baseline = if (targetLine < content.lineCount) {
          editor.getRowBaseline(targetLine).toFloat()
        } else {
          anchorBaseline + i * rowHeight
        }
        canvas.drawText(lineText, if (i == 0) firstX else leftX, baseline, ghostPaint)
      }
    } catch (_: Throwable) {
      // Never let suggestion rendering crash the editor.
    }
  }

  private companion object {
    private const val GHOST_COLOR = 0x80888888.toInt()
  }
}
