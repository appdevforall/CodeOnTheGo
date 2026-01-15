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

package com.itsaky.androidide.actions

import android.content.Context
import android.graphics.drawable.Drawable
import android.widget.EditText
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import com.itsaky.androidide.editor.ui.IDEEditor
import com.itsaky.androidide.resources.R
import com.itsaky.androidide.editor.adapters.IdeEditorAdapter
import com.itsaky.androidide.utils.resolveAttr

/** @author Akash Yadav */
abstract class BaseEditorAction : EditorActionItem {

  override var label: String = ""
  override var visible: Boolean = true
  override var enabled: Boolean = true
  override var icon: Drawable? = null
  override var requiresUIThread: Boolean = true // all editor actions must be executed on UI thread
  override var location: ActionItem.Location = ActionItem.Location.EDITOR_TEXT_ACTIONS

  override fun prepare(data: ActionData) {
    super.prepare(data)

    val textTarget = getTextTarget(data)
    val ideEditor = data.get(IDEEditor::class.java)

    if (textTarget != null) {
      visible = true
      enabled = textTarget.isEditable()
      return
    }

    if (ideEditor != null) {
      visible = true
      enabled = true
      return
    }

    visible = false
    enabled = visible
  }

  fun getTextTarget(data: ActionData): TextTarget? {
    val mutable = data.get(MutableTextTarget::class.java)
    if (mutable != null) return mutable

    val target = data.get(TextTarget::class.java)
    if (target != null) return target

    val editor = data.get(IDEEditor::class.java)
    if (editor != null) {
        return IdeEditorAdapter(editor)
    }

    val view = data.get(android.view.View::class.java)
    if (view is EditText) {
        return EditTextAdapter(view)
    }

    return null
  }

  fun getEditor(data: ActionData): IDEEditor? {
    return data.get(IDEEditor::class.java)
  }

  fun getContext(data: ActionData): Context? {
    return getTextTarget(data)?.getAnchorView()?.context
  }

  fun tintDrawable(context: Context, drawable: Drawable): Drawable {
    val wrapped = DrawableCompat.wrap(drawable).mutate()
    val solidColor = ContextCompat.getColor(context, R.color.primaryIconColor)

    wrapped.alpha = 255
    DrawableCompat.setTint(wrapped, solidColor)
    return wrapped
  }
}
