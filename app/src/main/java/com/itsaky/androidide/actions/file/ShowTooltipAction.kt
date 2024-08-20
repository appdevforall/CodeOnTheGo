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
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.itsaky.androidide.R
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.ActionItem
import com.itsaky.androidide.actions.EditorRelatedAction
import io.github.rosemoe.sora.widget.CodeEditor

class ShowTooltipAction(private val context: Context, override val order: Int) :
    EditorRelatedAction() {
    override val id: String = "ide.editor.code.text.format"
    override var location: ActionItem.Location = ActionItem.Location.EDITOR_TEXT_ACTIONS

    init {
        label = context.getString(R.string.title_show_tooltip)
        icon = ContextCompat.getDrawable(context, R.drawable.ic_android)
    }

    override suspend fun execAction(data: ActionData): Any {
        val editor = data.getEditor()!!
        val cursor = editor.text.cursor
        if (cursor.isSelected) {
            showTooltip(editor, editor.text.substring(cursor.left, cursor.right), "expand")
        }

        return true
    }

    private fun showTooltip(
        editor: CodeEditor,
        initialText: String,
        moreText: String,
    ) {
        // Inflate the custom layout for the tooltip
        val inflater = LayoutInflater.from(context)
        val tooltipView = inflater.inflate(R.layout.layout_tooltip, null)

        // Find views within the tooltip layout
        val titleText: TextView = tooltipView.findViewById(R.id.tooltip_text)
        val showMoreButton: Button = tooltipView.findViewById(R.id.btn_show_more)
        val expandedText: TextView = tooltipView.findViewById(R.id.tooltip_more_info)

        // Set initial text for the tooltip
        titleText.text = initialText

        // Set the expanded text
        expandedText.text = moreText

        // Handle the "Show More" button click
        showMoreButton.setOnClickListener {
            if (expandedText.visibility == View.GONE) {
                expandedText.visibility = View.VISIBLE
                showMoreButton.text = "Show Less"
            } else {
                expandedText.visibility = View.GONE
                showMoreButton.text = "Show More"
            }
        }

        val popupWindow = PopupWindow(
            tooltipView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        popupWindow.isOutsideTouchable = true
        popupWindow.isFocusable = true

        popupWindow.showAtLocation(editor, Gravity.CENTER, 0, 0)
    }
}
