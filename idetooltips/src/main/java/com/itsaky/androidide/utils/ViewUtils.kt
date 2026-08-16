package com.itsaky.androidide.utils

import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.View
import com.itsaky.androidide.idetooltips.TooltipManager

/**
 * Installs a long-click listener on this view that consumes the click, gives haptic
 * feedback, and shows [tooltipTag]'s tooltip (under [tooltipCategory]) anchored to [anchorView].
 */
fun View.displayTooltipOnLongPress(
	context: Context,
	anchorView: View,
	tooltipCategory: String,
	tooltipTag: String,
) {
	this.setOnLongClickListener {
		it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
		TooltipManager.showTooltip(
			context = context,
			anchorView = anchorView,
			category = tooltipCategory,
			tag = tooltipTag,
		)
		true
	}
}
