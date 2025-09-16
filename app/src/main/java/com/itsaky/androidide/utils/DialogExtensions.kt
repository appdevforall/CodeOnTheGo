package com.itsaky.androidide.utils

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import android.graphics.Rect
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.EditText
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

    val titleView: TextView? = dialog.findViewById(androidx.appcompat.R.id.alertTitle)
    val messageView: TextView? = dialog.findViewById(android.R.id.message)

    titleView?.setOnLongClickListener(longClickListener)
    messageView?.setOnLongClickListener(longClickListener)

    val customPanel: ViewGroup? = dialog.findViewById(androidx.appcompat.R.id.customPanel)

    @SuppressLint("ClickableViewAccessibility")
    fun applyListenerToEditTexts(view: View) {
        if (view is EditText) {
            view.setOnLongClickListener(longClickListener)

            dialog.setOnShowListener {
                view.requestFocus()
                val imm =
                    context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
            }

            dialog.window?.decorView?.setOnTouchListener { view, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    val outRect = Rect()
                    view.getGlobalVisibleRect(outRect)

                    // Check if the touch event is outside the bounds of the EditText
                    if (!outRect.contains(event.rawX.toInt(), event.rawY.toInt())) {
                        view.clearFocus()
                        // Hide the keyboard
                        val imm =
                            view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                        imm.hideSoftInputFromWindow(view.windowToken, 0)
                    }
                }
                // Return false to allow the event to be handled by other views
                false
            }
        }

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                applyListenerToEditTexts(view.getChildAt(i))
            }
        }
    }

    customPanel?.let {
        applyListenerToEditTexts(it)
    }

    return dialog
}