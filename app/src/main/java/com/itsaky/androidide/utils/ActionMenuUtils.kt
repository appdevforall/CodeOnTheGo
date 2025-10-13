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

package com.itsaky.androidide.utils

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.View.OnLongClickListener
import android.widget.FrameLayout.LayoutParams
import android.widget.PopupWindow
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.ActionItem
import com.itsaky.androidide.actions.ActionsRegistry
import com.itsaky.androidide.actions.internal.DefaultActionsRegistry
import com.itsaky.androidide.databinding.FileActionPopupWindowBinding
import com.itsaky.androidide.databinding.FileActionPopupWindowItemBinding
import com.itsaky.androidide.idetooltips.TooltipManager
import com.itsaky.androidide.idetooltips.TooltipTag

/**
 * Utility class to show a popup menu with [com.itsaky.androidide.actions.ActionsRegistry].
 *
 * @author Akash Yadav
 */
object ActionMenuUtils {

    fun showPopupWindow(context: Context, anchorView: View) {
        val registry = ActionsRegistry.getInstance()
        val actionData = ActionData.create(context)

        val binding =
            FileActionPopupWindowBinding.inflate(LayoutInflater.from(context), null, false)

        val popupWindow = PopupWindow(
            binding.root,
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT,
        ).apply {
            elevation = 2f
            isOutsideTouchable = true
        }

        val tooltipListener = OnLongClickListener { view ->
            TooltipManager.showTooltip(
                context = view.context,
                anchorView = view,
                tag = TooltipTag.DIALOG_FIND_IN_FILE_OPTIONS
            )
            popupWindow.dismiss()
            true
        }

        binding.root.setOnLongClickListener(tooltipListener)

        val actions = registry.getActions(ActionItem.Location.EDITOR_FILE_TABS)
        actions.forEach { action ->
            val itemView =
                FileActionPopupWindowItemBinding.inflate(
                    LayoutInflater.from(context),
                    null,
                    false
                ).root
            itemView.apply {
                text = action.value.label
                setOnClickListener {
                    (registry as DefaultActionsRegistry).executeAction(
                        action.value,
                        actionData
                    )
                    popupWindow.dismiss()
                }
                setOnLongClickListener {
                    TooltipManager.showTooltip(
                        context = context,
                        anchorView = anchorView,
                        tag = TooltipTag.EDITOR_FILE_CLOSE_OPTIONS
                    )
                    popupWindow.dismiss()
                    true
                }
            }
            binding.root.addView(itemView)
        }
        popupWindow.showAsDropDown(anchorView, 0, 0)

    }
}
