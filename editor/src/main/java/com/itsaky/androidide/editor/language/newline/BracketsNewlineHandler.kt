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

package com.itsaky.androidide.editor.language.newline

import io.github.rosemoe.sora.lang.smartEnter.NewlineHandleResult
import io.github.rosemoe.sora.lang.styling.Styles
import io.github.rosemoe.sora.text.CharPosition
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.text.TextUtils

internal open class BracketsNewlineHandler(
  val getIndentAdvance: (String?) -> Int,
  val useTab: () -> Boolean
) : CStyleBracketsHandler() {

  private val maxIndentColumns = 200

  override fun handleNewline(
    text: Content,
    position: CharPosition,
    style: Styles?,
    tabSize: Int
  ): NewlineHandleResult {
    val line = text.getLine(position.line)
    val index = position.column
    val beforeText = line.subSequence(0, index).toString()
    val afterText = line.subSequence(index, line.length).toString()
    return handleNewline(beforeText, afterText, tabSize)
  }

  private fun handleNewline(
    beforeText: String?,
    afterText: String?,
    tabSize: Int
  ): NewlineHandleResult {
    val advanceBefore: Int = getIndentAdvance(beforeText)
    val advanceAfter: Int = getIndentAdvance(afterText)

    val safeIndentBefore = advanceBefore.coerceIn(0, maxIndentColumns)
    val safeIndentAfter = advanceAfter.coerceIn(0, maxIndentColumns)

    var text: String
    val sb = StringBuilder(safeIndentBefore + safeIndentAfter + 4)
      .append('\n')
      .append(TextUtils.createIndent(safeIndentBefore, tabSize, useTab()))
      .append('\n')
      .append(TextUtils.createIndent(safeIndentAfter, tabSize, useTab()).also { text = it })

    val shiftLeft = text.length + 1
    return NewlineHandleResult(sb, shiftLeft)
  }
}
