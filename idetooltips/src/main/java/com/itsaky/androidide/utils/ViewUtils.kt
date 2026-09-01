package com.itsaky.androidide.utils

import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.View
import com.itsaky.androidide.idetooltips.TooltipCategory
import com.itsaky.androidide.idetooltips.TooltipManager

/**
 * Shows [tag]'s tooltip (under [category]) anchored to [anchor], or does nothing if [tag] is
 * blank.
 *
 * [playHapticFeedback] defaults to `true` for callers driving this from a mechanism (e.g. a
 * [GestureDetector]-based long-press) that doesn't already get the platform's own long-press
 * haptic. Pass `false` when calling this from a [View.OnLongClickListener] or
 * `AdapterView.OnItemLongClickListener` that returns `true` - the platform already fires the
 * identical feedback for those, and a manual call here would double-buzz.
 */
fun showTooltipIfPresent(
	context: Context,
	anchor: View,
	category: String,
	tag: String,
	playHapticFeedback: Boolean = true,
) {
	if (tag.isNotBlank()) {
		if (playHapticFeedback) {
			anchor.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
		}
		TooltipManager.showTooltip(context, anchor, category, tag)
	}
}

/** Shows [tag]'s IDE-category tooltip anchored to [anchor]. See [showTooltipIfPresent]. */
fun showIdeCategoryTooltipIfPresent(
	context: Context,
	anchor: View,
	tag: String,
	playHapticFeedback: Boolean = true,
) = showTooltipIfPresent(context, anchor, TooltipCategory.CATEGORY_IDE, tag, playHapticFeedback)

/**
 * Installs a long-click listener on this view that consumes the click and shows [tooltipTag]'s
 * tooltip (under [tooltipCategory]) anchored to this view, or does nothing if [tooltipTag] is
 * blank. See [showTooltipIfPresent] - no manual haptic feedback here for the same reason.
 */
fun View.displayTooltipOnLongPress(
	context: Context,
	tooltipTag: String,
	tooltipCategory: String = TooltipCategory.CATEGORY_IDE,
) {
	this.setOnLongClickListener {
		showTooltipIfPresent(context, this, tooltipCategory, tooltipTag, playHapticFeedback = false)
		true
	}
}
