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
import android.widget.LinearLayout
import android.widget.PopupWindow
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.ActionItem
import com.itsaky.androidide.actions.ActionsRegistry
import com.itsaky.androidide.actions.internal.DefaultActionsRegistry
import com.itsaky.androidide.databinding.FileActionPopupWindowBinding
import com.itsaky.androidide.databinding.FileActionPopupWindowItemBinding
import com.itsaky.androidide.idetooltips.TooltipManager
import com.itsaky.androidide.idetooltips.TooltipTag
import com.itsaky.androidide.plugins.extensions.FileTabMenuItem

object ActionMenuUtils {
	/**
	 * Caps [this] at the room actually available below [anchorView], measuring [content] first.
	 *
	 * A PopupWindow built WRAP_CONTENT reports height -2 to `showAsDropDown`, and its fit check
	 * (`height <= spaceBelow`) is therefore trivially satisfied -- so no resize is ever negotiated and
	 * the content gets measured against the whole display instead of the space under the tab strip.
	 * The ScrollView then believes it fits and does not scroll, and the window is left to run off the
	 * bottom or be shoved up over the tabs and toolbar. At 2x font scale with the undock item and
	 * plugin contributions, this menu can reach that size.
	 *
	 * Left alone when the content already fits, so the failure mode of a bad measurement is the
	 * previous behaviour rather than a zero-height popup.
	 */
	internal fun PopupWindow.capHeightToSpaceBelow(
		anchorView: View,
		content: View,
	) {
		val available = getMaxAvailableHeight(anchorView)
		if (available <= 0) return

		val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
		content.measure(unspecified, unspecified)
		if (content.measuredHeight > available) {
			height = available
		}
	}

	fun showPopupWindow(
		context: Context,
		anchorView: View,
		pluginMenuItems: List<FileTabMenuItem> = emptyList(),
	) {
		val registry = ActionsRegistry.getInstance()
		val actionData = ActionData.create(context)

		val binding =
			FileActionPopupWindowBinding.inflate(LayoutInflater.from(context), null, false)

		val popupWindow =
			PopupWindow(
				binding.root,
				LayoutParams.WRAP_CONTENT,
				LayoutParams.WRAP_CONTENT,
			).apply {
				elevation = 2f
				isOutsideTouchable = true
			}

		val tooltipListener =
			OnLongClickListener { view ->
				TooltipManager.showIdeCategoryTooltip(
					context = view.context,
					anchorView = view,
					tag = TooltipTag.DIALOG_FIND_IN_FILE_OPTIONS,
				)
				popupWindow.dismiss()
				true
			}

		binding.actionItems.setOnLongClickListener(tooltipListener)

		val actions = registry.getActions(ActionItem.Location.EDITOR_FILE_TABS)
		actions.forEach { action ->
			action.value.prepare(actionData)
			if (!action.value.visible || !action.value.enabled) return@forEach

			val itemView =
				FileActionPopupWindowItemBinding
					.inflate(
						LayoutInflater.from(context),
						null,
						false,
					).root
			itemView.apply {
				text = action.value.label
				setOnClickListener {
					(registry as DefaultActionsRegistry).executeAction(
						action.value,
						actionData,
					)
					popupWindow.dismiss()
				}
				setOnLongClickListener {
					TooltipManager.showIdeCategoryTooltip(
						context = context,
						anchorView = anchorView,
						tag = TooltipTag.EDITOR_FILE_CLOSE_OPTIONS,
					)
					popupWindow.dismiss()
					true
				}
			}
			binding.actionItems.addView(itemView)
		}

		val visiblePluginItems = pluginMenuItems.filter { it.isEnabled && it.isVisible }
		if (visiblePluginItems.isNotEmpty()) {
			val divider =
				View(context).apply {
					layoutParams =
						LinearLayout
							.LayoutParams(
								LinearLayout.LayoutParams.MATCH_PARENT,
								1,
							).apply {
								// dp, not raw pixels: a literal 8 is 8dp on a 1x device and 2.7dp on a 3x one.
								topMargin = context.dpToPx(8f)
								bottomMargin = context.dpToPx(8f)
							}
					val typedValue = android.util.TypedValue()
					context.theme.resolveAttribute(
						com.google.android.material.R.attr.colorOutline,
						typedValue,
						true,
					)
					setBackgroundColor(typedValue.data)
				}
			binding.actionItems.addView(divider)

			visiblePluginItems.forEach { item ->
				val itemView =
					FileActionPopupWindowItemBinding
						.inflate(
							LayoutInflater.from(context),
							null,
							false,
						).root
				itemView.apply {
					text = item.title
					setOnClickListener {
						try {
							item.action()
						} catch (e: Exception) {
							android.util.Log.e("ActionMenuUtils", "Plugin menu action failed", e)
						}
						popupWindow.dismiss()
					}
					item.tooltipTag?.let { tag ->
						setOnLongClickListener {
							TooltipManager.showIdeCategoryTooltip(
								context = context,
								anchorView = anchorView,
								tag = tag,
							)
							popupWindow.dismiss()
							true
						}
					}
				}
				binding.actionItems.addView(itemView)
			}
		}

		popupWindow.capHeightToSpaceBelow(anchorView, binding.root)
		popupWindow.showAsDropDown(anchorView, 0, 0)
	}
}
