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

import com.itsaky.androidide.editor.language.treesitter.TreeSitterLanguage
import com.itsaky.androidide.editor.language.utils.IndentCache
import io.github.rosemoe.sora.lang.smartEnter.NewlineHandleResult
import io.github.rosemoe.sora.lang.styling.Styles
import io.github.rosemoe.sora.text.CharPosition
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.text.TextUtils

/**
 * Newline handler for tree-sitter languages.
 *
 * @author Akash Yadav
 */
abstract class TSBracketsHandler(private val language: TreeSitterLanguage) : BaseNewlineHandler() {

  private val indentCache = IndentCache()
  val maxIndentColumns = IndentCache.MAX_INDENT_COLUMNS

  override fun handleNewline(
    text: Content,
    position: CharPosition,
    style: Styles?,
    tabSize: Int
  ): NewlineHandleResult {
    val lineText = text.getLine(position.line)

    val baseIndent = TextUtils.countLeadingSpaceCount(lineText, tabSize)
      .coerceIn(0, maxIndentColumns)

    val indentPlusTab = (baseIndent + tabSize).coerceIn(0, maxIndentColumns)
    val useTabNow = language.useTab()
    val innerIndent = indentCache.indent(indentPlusTab, tabSize, useTabNow)
    val outerIndent = indentCache.indent(baseIndent, tabSize, useTabNow)

    val sb = StringBuilder(2 + innerIndent.length + outerIndent.length)
      .append('\n')
      .append(innerIndent)
      .append('\n')
      .append(outerIndent)
    return NewlineHandleResult(sb, outerIndent.length + 1)
  }
}
