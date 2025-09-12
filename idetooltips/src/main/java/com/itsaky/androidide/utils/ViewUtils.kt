package com.itsaky.androidide.utils

import android.content.Context
import android.view.View
import com.itsaky.androidide.idetooltips.TooltipManager

fun View.displayTooltipOnLongPress(
    context: Context,
    anchorView: View,
    tooltipCategory: String,
    tooltipTag: String
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
