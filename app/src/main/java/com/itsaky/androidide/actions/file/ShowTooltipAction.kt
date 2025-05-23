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

package com.itsaky.androidide.actions.file

import android.content.Context
import androidx.core.content.ContextCompat
import com.itsaky.androidide.R
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.ActionItem
import com.itsaky.androidide.actions.EditorRelatedAction
import com.itsaky.androidide.idetooltips.IDETooltipItem
import com.itsaky.androidide.utils.TooltipUtils

class ShowTooltipAction(private val context: Context, override val order: Int) :
    EditorRelatedAction() {
    override val id: String = "ide.editor.code.text.format"
    override var location: ActionItem.Location = ActionItem.Location.EDITOR_TEXT_ACTIONS
    private var htmlString: String = ""

    init {
        label = context.getString(R.string.title_show_tooltip)
        icon = ContextCompat.getDrawable(context, R.drawable.ic_action_help)
    }

    override suspend fun execAction(data: ActionData): Any {
        val editor = data.getEditor()!!
        val cursor = editor.text.cursor
        val activity = data.getActivity()
        val category = when(editor.file!!.extension.toString()) {
            "java" -> "java"
            "kt" -> "kotlin"
            else -> "ide"
        }
        val word = editor.text.substring(cursor.left, cursor.right)
        if (cursor.isSelected) {
            activity?.getTooltipData(category, word)?.let { tooltipData ->
                TooltipUtils.showIDETooltip(
                    context,
                    editor,
                    0,
                    IDETooltipItem(
                        tooltipCategory = category,
                        tooltipTag = tooltipData.tooltipTag,
                        detail = tooltipData.detail,
                        summary = tooltipData.summary,
                        buttons = tooltipData.buttons,
                    ),
                )
            }
        }
        return true
    }

}
