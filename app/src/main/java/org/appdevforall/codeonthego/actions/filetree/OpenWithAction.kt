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

package org.appdevforall.codeonthego.actions.filetree

import android.content.Context
import android.content.Intent
import org.appdevforall.codeonthego.actions.ActionData
import org.appdevforall.codeonthego.actions.markInvisible
import org.appdevforall.codeonthego.actions.requireFile
import org.appdevforall.codeonthego.idetooltips.TooltipTag
import com.itsaky.androidide.resources.R
import org.appdevforall.codeonthego.utils.IntentUtils

/**
 * File tree action to open files with external applications.
 *
 * @author Akash Yadav
 */
class OpenWithAction(context: Context, override val order: Int) :
  BaseFileTreeAction(
    context = context,
    labelRes = R.string.open_with,
    iconRes = R.drawable.ic_open_with
  ) {

  override val id: String = "ide.editor.fileTree.openWith"

  override fun retrieveTooltipTag(isAlternateContext: Boolean): String =
    TooltipTag.PROJECT_FILE_OPENWITH

  override fun prepare(data: ActionData) {
    super.prepare(data)
    
    // Hide "Open with" option for directories
    val file = data.requireFile()
    if (file.isDirectory) {
      markInvisible()
    }
  }

  override suspend fun execAction(data: ActionData) {
    IntentUtils.startIntent(data.requireActivity(), data.requireFile(), "*/*", Intent.ACTION_VIEW)
  }
}
