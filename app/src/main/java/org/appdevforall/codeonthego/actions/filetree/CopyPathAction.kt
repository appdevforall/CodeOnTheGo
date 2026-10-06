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
import org.appdevforall.codeonthego.actions.ActionData
import org.appdevforall.codeonthego.actions.requireContext
import org.appdevforall.codeonthego.actions.requireFile
import org.appdevforall.codeonthego.idetooltips.TooltipTag
import com.itsaky.androidide.resources.R
import org.appdevforall.codeonthego.utils.copyToClipboard
import org.appdevforall.codeonthego.utils.flashSuccess

/**
 * Action to copy the absolute path of the selected file.
 *
 * @author Akash Yadav
 */
class CopyPathAction(
	context: Context,
	override val order: Int,
) : BaseFileTreeAction(context, labelRes = R.string.copy_path, iconRes = R.drawable.ic_copy) {
	override val id: String = "ide.editor.fileTree.copyPath"

	override fun retrieveTooltipTag(isAlternateContext: Boolean): String = TooltipTag.PROJECT_ITEM_COPYPATH

	override suspend fun execAction(data: ActionData) {
		val file = data.requireFile()
		data.requireContext().copyToClipboard(file.absolutePath, label = "[AndroidIDE] Copied File Path")
		flashSuccess(R.string.copied)
	}
}
