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
import android.graphics.Rect
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
								context.dpToPx(1f),
							).apply {
								// dp, not raw pixels -- height included: a literal 1 is 0.33dp on a 3x device.
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

/**
 * Caps [this] at the space below [anchorView], measuring [content] at the width the window will
 * actually be given.
 *
 * A PopupWindow built WRAP_CONTENT reports height -2 to `showAsDropDown`, whose fit check is
 * `height <= spaceBelow` -- trivially true for -2. No resize is negotiated, so the content is
 * measured against the whole display rather than the room under the anchor, the ScrollView concludes
 * it fits and never scrolls, and the window either runs off the bottom or is shoved up over the tab
 * strip and toolbar. At 2x font scale this menu can reach that size.
 *
 * Two things here are easy to get wrong, and the first version of this function got both:
 *
 * `PopupWindow.getMaxAvailableHeight` is NOT the space below. Every overload delegates to the
 * three-argument form, which returns `Math.max(distanceToBottom, distanceToTop)` (AOSP android-36
 * `PopupWindow.java:2010`) -- so for an anchor low in the frame it yields the space ABOVE, and
 * capping to that lets `showAsDropDown` flip the popup over the anchor at exactly the height that
 * covers the tabs. The distance is computed directly instead, mirroring AOSP's own
 * `distanceToBottom` for the non-`mOverlapAnchor` case.
 *
 * The width spec matters as much as the height. Measuring with UNSPECIFIED width lets every label
 * lay out on one unbounded line, so a title that wraps in the real pass reports a fraction of its
 * laid-out height and the cap is skipped in the case it exists for -- a long plugin title at 2x. The
 * width is bounded by the visible frame; that is still slightly generous, since the popup background
 * and the item container's 24dp padding narrow it further, but it errs toward capping rather than
 * skipping.
 *
 * Left alone when the content already fits, so a bad measurement degrades to the previous behaviour
 * rather than a clipped or zero-height popup.
 */
internal fun PopupWindow.capHeightToSpaceBelow(
	anchorView: View,
	content: View,
) {
	val visibleFrame = Rect()
	anchorView.getWindowVisibleDisplayFrame(visibleFrame)

	val anchorOnScreen = IntArray(2)
	anchorView.getLocationOnScreen(anchorOnScreen)
	val spaceBelow = visibleFrame.bottom - (anchorOnScreen[1] + anchorView.height)
	if (spaceBelow <= 0) return

	content.measure(
		View.MeasureSpec.makeMeasureSpec(visibleFrame.width(), View.MeasureSpec.AT_MOST),
		View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
	)

	if (content.measuredHeight > spaceBelow) {
		height = spaceBelow
	}
}
