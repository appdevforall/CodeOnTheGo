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

package org.appdevforall.codeonthego.actions.sidebar

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import org.appdevforall.codeonthego.actions.ActionData
import org.appdevforall.codeonthego.actions.requireContext
import org.appdevforall.codeonthego.activities.editor.BaseEditorActivity
import org.appdevforall.codeonthego.idetooltips.TooltipTag
import com.itsaky.androidide.resources.R
import kotlin.reflect.KClass

/**
 * Sidebar action for closing the project.
 *
 * @author Akash Yadav
 */
class CloseProjectSidebarAction(context: Context, override val order: Int) :
  AbstractSidebarAction() {

  override val id: String = "ide.editor.sidebar.closeProject"
  override val fragmentClass: KClass<out Fragment>? = null

  init {
    label = context.getString(R.string.title_close_project)
    icon = ContextCompat.getDrawable(context, R.drawable.ic_folder_close)
  }

  override suspend fun execAction(data: ActionData): Any {
    val context = data.requireContext() as BaseEditorActivity
    context.doConfirmProjectClose()
    return true
  }
  override fun retrieveTooltipTag(isAlternateContext: Boolean) = TooltipTag.CLOSE_PROJECT_SIDEBAR
}