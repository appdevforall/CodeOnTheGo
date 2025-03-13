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
import android.view.View
import androidx.appcompat.widget.TooltipCompat
import com.itsaky.androidide.activities.editor.EditorHandlerActivity
import com.itsaky.androidide.tasks.cancelIfActive
import com.itsaky.androidide.utils.TooltipUtils
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.plus

/** @author Akash Yadav */
abstract class EditorActivityAction : ActionItem {

  override var enabled: Boolean = true
  override var visible: Boolean = true
  override var icon: Drawable? = null
  override var label: String = ""
  override var location: ActionItem.Location = ActionItem.Location.EDITOR_TOOLBAR

  override var requiresUIThread: Boolean = false

  protected val actionScope = CoroutineScope(Dispatchers.Default) +
      CoroutineName("${javaClass.simpleName}Scope")

  override fun prepare(data: ActionData) {
    super.prepare(data)
    if (!data.hasRequiredData(Context::class.java)) {
      markInvisible()
    }
  }

  fun ActionData.getActivity(): EditorHandlerActivity? {
    return this[Context::class.java] as? EditorHandlerActivity
  }

  fun ActionData.requireActivity(): EditorHandlerActivity {
    return getActivity()!!
  }

  override fun destroy() {
    super.destroy()
    actionScope.cancelIfActive("Action is being destroyed")
  }

  /**
   * Configura el tooltip personalizado para una vista.
   * @param view La vista a la que se le asignará el tooltip.
   */
  fun setupTooltip(view: View) {
    println("_______ Setting up tooltip for view: ${view.javaClass.simpleName}") // Mensaje de depuración
    view.setOnLongClickListener {
      println("_______ Long click detected") // Mensaje de depuración
      TooltipUtils.showIDETooltip(
        view.context,
        view,
        0,
        label,
        "More information about $label",
        arrayListOf(Pair("Learn more", "~/help_top.html"))
      ) { url ->
        TooltipUtils.showWebPage(view.context, url)
      }
      true
    }
  }

  /**
   * Muestra el tooltip personalizado.
   * @param view La vista en la que se mostrará el tooltip.
   */
  public fun showCustomTooltip(view: View) {
    println("_______  long click")
    TooltipUtils.showIDETooltip(
      view.context,
      view,
      0,
      label, // Usar el label de la acción como mensaje del tooltip
      "More information about $label", // Descripción adicional
      arrayListOf(Pair("Learn more", "~/help_top.html"))
    ) { url ->
      TooltipUtils.showWebPage(view.context, url)
    }
  }
}
