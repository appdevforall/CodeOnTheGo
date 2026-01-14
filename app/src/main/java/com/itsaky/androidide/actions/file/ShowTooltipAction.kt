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
import com.itsaky.androidide.actions.BaseEditorAction
import com.itsaky.androidide.editor.utils.isXmlAttribute
import com.itsaky.androidide.idetooltips.TooltipCategory
import com.itsaky.androidide.idetooltips.TooltipManager
import com.itsaky.androidide.idetooltips.TooltipTag

class ShowTooltipAction(private val context: Context, override val order: Int) :
    BaseEditorAction() {

    override val id: String = "ide.editor.code.text.show_tooltip"
    override var location: ActionItem.Location = ActionItem.Location.EDITOR_TEXT_ACTIONS

    init {
        label = context.getString(R.string.title_show_tooltip)
        val drawable = ContextCompat.getDrawable(context, R.drawable.ic_action_help)
        icon = drawable?.let { tintDrawable(context, it) }
    }

    override fun prepare(data: ActionData) {
        super.prepare(data)
        if (!visible) return

        val target = getTextTarget(data)
        visible = target != null
        enabled = visible
    }

    override suspend fun execAction(data: ActionData): Boolean {
        val target = getTextTarget(data) ?: return false
        val anchorView = target.getAnchorView() ?: return false
        val editor = getEditor(data)

        val category: String
        val tag: String

        if (editor != null) {
            val selectedText = target.getSelectedText()
            category = when (editor.file?.extension) {
                "java" -> TooltipCategory.CATEGORY_JAVA
                "kt" -> TooltipCategory.CATEGORY_KOTLIN
                "xml" -> TooltipCategory.CATEGORY_XML
                else -> TooltipCategory.CATEGORY_IDE
            }

            val useEditorTag = editor.tag != null
            val textToUse = selectedText ?: ""
            tag = when {
                useEditorTag -> editor.tag.toString()
                category == TooltipCategory.CATEGORY_XML && editor.isXmlAttribute() -> textToUse.substringAfterLast(":")
                else -> textToUse
            }
        } else {
            category = TooltipCategory.CATEGORY_IDE
            tag = TooltipTag.DIALOG_FIND_IN_PROJECT
        }

        if (tag.isEmpty()) return false

        TooltipManager.showTooltip(
            context = anchorView.context,
            anchorView = anchorView,
            category = category,
            tag = tag,
        )

        return true
    }

    override fun retrieveTooltipTag(isAlternateContext: Boolean) = TooltipTag.EDITOR_TOOLBAR_HELP
}