package com.itsaky.androidide.fragments

import android.content.Context
import android.content.res.Resources
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import android.widget.TextView
import com.itsaky.androidide.R
import com.itsaky.androidide.actions.ActionData

class FindActionDialog(
    private val anchor: View,
    context: Context,
    private val actionData: ActionData,
    shouldShowFindInFileAction: Boolean,
    private val onFindInFileClicked: ((ActionData) -> Unit),
    private val onFindInProjectClicked: ((ActionData) -> Unit)
) {
    private val popupWindow: PopupWindow

    init {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_find_action_menu, null)
        val findInFileText = view.findViewById<TextView>(R.id.find_in_file)
        val findInProjectText = view.findViewById<TextView>(R.id.find_in_project)

        // Hide find in file if no files are open
        findInFileText.visibility = if (shouldShowFindInFileAction) View.VISIBLE else View.GONE

        findInFileText.setOnClickListener {
            popupWindow.dismiss()
            onFindInFileClicked(actionData)
        }

        findInProjectText.setOnClickListener {
            popupWindow.dismiss()
            onFindInProjectClicked(actionData)
        }

        popupWindow = PopupWindow(
            view,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            isOutsideTouchable = true
            elevation = 16f
        }
    }

    fun show() {
        anchor.post {
            val screenWidth = Resources.getSystem().displayMetrics.widthPixels
            val offsetX = 0 // no horizontal padding
            val offsetY = 0 // top of the screen

            // Measure the popup width
            popupWindow.contentView.measure(
                View.MeasureSpec.UNSPECIFIED,
                View.MeasureSpec.UNSPECIFIED
            )
            val popupWidth = popupWindow.contentView.measuredWidth

            // Calculate final X position for right alignment
            val x = screenWidth - popupWidth - offsetX

            // Show at top-right
            popupWindow.showAtLocation(anchor, Gravity.TOP or Gravity.START, x, offsetY)
        }
    }
}
