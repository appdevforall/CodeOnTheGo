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

package com.itsaky.androidide.actions.editor

import android.content.Context
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.BaseEditorAction
import com.itsaky.androidide.actions.MutableTextTarget
import com.itsaky.androidide.idetooltips.TooltipTag
/** @author Akash Yadav */
class CutAction(context: Context, override val order: Int) : BaseEditorAction() {

  init {
    label = context.getString(android.R.string.cut)

    val arr = context.obtainStyledAttributes(intArrayOf(android.R.attr.actionModeCutDrawable))
    icon = arr.getDrawable(0)?.let { tintDrawable(context, it) }
    arr.recycle()
  }

  companion object {
    const val ID = "ide.editor.code.text.cut"
  }

  override fun prepare(data: ActionData) {
    super.prepare(data)

    if (!visible) return

    val target = getTextTarget(data)
    visible = (target is MutableTextTarget) && target.isEditable()
    enabled = visible && target?.hasSelection() == true
  }

  override val id: String = ID
  override suspend fun execAction(data: ActionData): Boolean {
    val target = getTextTarget(data) ?: return false
    if (target is MutableTextTarget) {
      target.cut()
      return true
    }
    return false
  }
  override fun retrieveTooltipTag(isAlternateContext: Boolean) = TooltipTag.EDITOR_TOOLBAR_CUT
}
