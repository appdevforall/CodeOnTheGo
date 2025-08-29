package com.itsaky.androidide.utils

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
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
    anchorView: View? = null
): AlertDialog {
    val dialog = this.create()
    dialog.show()

    val workingAnchorView = anchorView ?: (context as? Activity)?.window?.decorView ?: return dialog
    val lifecycleOwner = context as? LifecycleOwner
        ?: run {
            Log.w("DialogExtensions", "Context is not a LifecycleOwner, cannot show tooltip.")
            return dialog // Return the dialog without the gesture
        }

    val gestureDetector =
        GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) {
                dialog.dismiss()
                lifecycleOwner.lifecycleScope.launch {
                    try {
                        val tooltipData = TooltipManager.getTooltip(
                            context,
                            TooltipCategory.CATEGORY_IDE,
                            tooltipTag
                        )
                        tooltipData?.let {
                            TooltipUtils.showIDETooltip(
                                context = context,
                                level = 0,
                                tooltipItem = it,
                                anchorView = workingAnchorView
                            )
                        }
                    } catch (e: Exception) {
                        Log.e("Tooltip", "Error showing tooltip for $tooltipTag", e)
                    }
                }
            }
        })

    val universalTouchListener = View.OnTouchListener { view, event ->
        val wasGestureHandled = gestureDetector.onTouchEvent(event)
        if (event.action == MotionEvent.ACTION_UP && !wasGestureHandled) {
            view.performClick()
        }
        true
    }

    dialog.window?.decorView?.setOnTouchListener(universalTouchListener)
    @SuppressLint("ClickableViewAccessibility")
    dialog.findViewById<TextView>(android.R.id.message)?.setOnTouchListener(universalTouchListener)
    @SuppressLint("ClickableViewAccessibility")
    dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.setOnTouchListener(universalTouchListener)
    @SuppressLint("ClickableViewAccessibility")
    dialog.getButton(DialogInterface.BUTTON_NEGATIVE)?.setOnTouchListener(universalTouchListener)

    return dialog
}