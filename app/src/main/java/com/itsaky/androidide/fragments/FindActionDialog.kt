package com.itsaky.androidide.fragments

import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.itsaky.androidide.R
import com.itsaky.androidide.actions.ActionData

class FindActionDialog(
    private val anchor: View,
    private val context: Context,
    private val actionData: ActionData,
    shouldMarkInvisible: Boolean,
    private val onFindInFileClicked: ((ActionData) -> Unit),
    private val onFindInProjectClicked: (() -> Unit)
) {
    private val popupWindow: PopupWindow

    init {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_find_action_menu, null)
        val findInFileText = view.findViewById<TextView>(R.id.find_in_file)
        val findInProjectText = view.findViewById<TextView>(R.id.find_in_project)

        // Hide find in file if no files are open
        findInFileText.visibility = if (shouldMarkInvisible) View.GONE else View.VISIBLE

        findInFileText.setOnClickListener {
            popupWindow.dismiss()
            onFindInFileClicked(actionData)
        }

        findInProjectText.setOnClickListener {
            popupWindow.dismiss()
            onFindInProjectClicked()
        }

        popupWindow = PopupWindow(
            view,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            isOutsideTouchable = true
            elevation = 8f
            setBackgroundDrawable(ContextCompat.getDrawable(context, android.R.color.transparent))
        }
    }

    fun show() {
        anchor.post {
            val location = IntArray(2)
            anchor.getLocationOnScreen(location)
            popupWindow.showAtLocation(
                anchor,
                Gravity.NO_GRAVITY,
                location[0],
                location[1] + anchor.height
            )
        }
    }
}
