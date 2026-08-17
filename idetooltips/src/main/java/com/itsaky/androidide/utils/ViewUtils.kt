package com.itsaky.androidide.utils

import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.View
import com.itsaky.androidide.idetooltips.TooltipManager

/**
 * Installs a long-click listener on this view that consumes the click and shows [tooltipTag]'s
 * tooltip (under [tooltipCategory]) anchored to [anchorView].
 *
 * No manual haptic feedback here: the platform already calls `performHapticFeedback` for a
 * [View.OnLongClickListener] that returns `true` (see `View.performLongClick()`), so adding
 * another call here would double-buzz.
 */
fun View.displayTooltipOnLongPress(
	context: Context,
	anchorView: View,
	tooltipCategory: String,
	tooltipTag: String,
) {
	this.setOnLongClickListener {
		TooltipManager.showTooltip(
			context = context,
			anchorView = anchorView,
			category = tooltipCategory,
			tag = tooltipTag,
		)
		true
	}
}

/**
 * Shows [tag]'s IDE-category tooltip anchored to [anchor], or does nothing if [tag] is blank.
 *
 * [playHapticFeedback] defaults to `true` for callers driving this from a mechanism (e.g. a
 * [GestureDetector]-based long-press) that doesn't already get the platform's own long-press
 * haptic. Pass `false` when calling this from a [View.OnLongClickListener] or
 * `AdapterView.OnItemLongClickListener` that returns `true` - the platform already fires the
 * identical feedback for those, and a manual call here would double-buzz.
 */
fun showIdeCategoryTooltipIfPresent(
	context: Context,
	anchor: View,
	tag: String,
	playHapticFeedback: Boolean = true,
) {
	if (tag.isNotBlank()) {
		if (playHapticFeedback) {
			anchor.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
		}
		TooltipManager.showIdeCategoryTooltip(context, anchor, tag)
	}
}
