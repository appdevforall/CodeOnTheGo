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
import com.itsaky.androidide.editor.utils.isJavaOperatorToken
import com.itsaky.androidide.editor.utils.isKotlinOperatorToken
import com.itsaky.androidide.editor.utils.isXmlAttribute
import com.itsaky.androidide.idetooltips.TooltipCategory
import com.itsaky.androidide.idetooltips.TooltipManager
import com.itsaky.androidide.idetooltips.TooltipTag

class ShowTooltipAction(private val context: Context, override val order: Int) :
    BaseEditorAction() {

    companion object {
      const val ID = "ide.editor.code.text.show_tooltip"
    }

    override val id: String = ID
    override var location: ActionItem.Location = ActionItem.Location.EDITOR_TEXT_ACTIONS

    init {
        label = context.getString(R.string.title_show_tooltip)
        val drawable = ContextCompat.getDrawable(context, R.drawable.ic_action_help_outlined)
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

        val categoryAndTag =
            if (editor != null) {
                val category = tooltipCategoryForExtension(editor.file?.extension)
                resolveTooltipTag(
                    category = category,
                    selectedText = target.getSelectedText(),
                    editorTag = editor.tag?.toString(),
                    isXmlAttribute = category == TooltipCategory.CATEGORY_XML && editor.isXmlAttribute(),
                ).let { tag -> category to tag }
            } else {
                TooltipCategory.CATEGORY_IDE to TooltipTag.DIALOG_FIND_IN_PROJECT
            }
        val (category, tag) = categoryAndTag

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

internal fun tooltipCategoryForExtension(extension: String?): String =
    when (extension) {
        "java" -> TooltipCategory.CATEGORY_JAVA
        "kt" -> TooltipCategory.CATEGORY_KOTLIN
        "xml" -> TooltipCategory.CATEGORY_XML
        else -> TooltipCategory.CATEGORY_IDE
    }

internal fun resolveTooltipTag(
    category: String,
    selectedText: String?,
    editorTag: String?,
    isXmlAttribute: Boolean,
): String {
    val textToUse = selectedText ?: ""
    return when {
        !editorTag.isNullOrEmpty() -> editorTag
        category == TooltipCategory.CATEGORY_XML && isXmlAttribute -> textToUse.substringAfterLast(":")
        category == TooltipCategory.CATEGORY_KOTLIN && isKotlinOperatorToken(textToUse) -> "kotlin.operator.$textToUse"
        category == TooltipCategory.CATEGORY_JAVA && isJavaOperatorToken(textToUse) -> "java.operator.$textToUse"
        else -> textToUse
    }
}