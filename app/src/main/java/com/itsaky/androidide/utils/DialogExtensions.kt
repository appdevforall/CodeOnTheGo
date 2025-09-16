package com.itsaky.androidide.utils

import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import android.util.Log
import android.view.View
import android.widget.AdapterView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.itsaky.androidide.idetooltips.TooltipCategory
import com.itsaky.androidide.idetooltips.TooltipManager
import kotlinx.coroutines.launch

fun MaterialAlertDialogBuilder.showWithLongPressTooltip(
    context: Context,
    tooltipTag: String,
    vararg customViews: View
): AlertDialog {
    val dialog = this.create()

    val lifecycleOwner = context as? LifecycleOwner
        ?: run {
            Log.w("DialogExtensions", "Context is not a LifecycleOwner; cannot show tooltip.")
            dialog.show()
            return dialog
        }

    fun longPressAction() {
        dialog.dismiss()
        lifecycleOwner.lifecycleScope.launch {
            try {
                val tooltipData = TooltipManager.getTooltip(
                    context,
                    TooltipCategory.CATEGORY_IDE,
                    tooltipTag
                )
                val anchor = (context as? Activity)?.window?.decorView ?: return@launch
                tooltipData?.let {
                    TooltipUtils.showIDETooltip(
                        context = context,
                        level = 0,
                        tooltipItem = it,
                        anchorView = anchor
                    )
                }
            } catch (e: Exception) {
                Log.e("Tooltip", "Error showing tooltip for $tooltipTag", e)
            }
        }
    }

    val longClickListener = View.OnLongClickListener {
        longPressAction()
        true
    }

    customViews.forEach { view ->
        view.setOnLongClickListener(longClickListener)
    }

    dialog.show()

    dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.setOnLongClickListener(longClickListener)
    dialog.getButton(DialogInterface.BUTTON_NEGATIVE)?.setOnLongClickListener(longClickListener)
    dialog.getButton(DialogInterface.BUTTON_NEUTRAL)?.setOnLongClickListener(longClickListener)

    dialog.listView?.onItemLongClickListener =
        AdapterView.OnItemLongClickListener { _, _, _, _ ->
            longPressAction()
            true
        }

    return dialog
}