package com.itsaky.androidide.utils

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.itsaky.androidide.idetooltips.TooltipManager

@SuppressLint("ClickableViewAccessibility")
fun MaterialAlertDialogBuilder.showWithLongPressTooltip(
    context: Context,
    tooltipTag: String,
    vararg customViews: View
): AlertDialog {
    val dialog = this.create()

    fun longPressAction() {
        dialog.dismiss()
        val anchor = (context as? Activity)?.window?.decorView ?: return
        TooltipManager.showTooltip(
            context = context,
            anchorView = anchor,
            tag = tooltipTag,
        )
    }

    dialog.onLongPress {
        longPressAction()
        true
    }

    dialog.listView?.onItemLongClickListener =
        AdapterView.OnItemLongClickListener { _, _, _, _ ->
            longPressAction()
            true
        }

    val customPanel: ViewGroup? = dialog.findViewById(androidx.appcompat.R.id.customPanel)

    customPanel?.forEachViewRecursively { view ->
        if (view is EditText) {
            dialog.setOnShowListener {
                view.requestFocus()
                val imm =
                    context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
            }

            dialog.window?.decorView?.setOnTouchListener { v, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    val outRect = Rect()
                    view.getGlobalVisibleRect(outRect)
                    if (!outRect.contains(event.rawX.toInt(), event.rawY.toInt())) {
                        view.clearFocus()
                        val imm =
                            view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                        imm.hideSoftInputFromWindow(view.windowToken, 0)
                    }
                }
                false
            }
        }
    }

    dialog.show()
    return dialog
}