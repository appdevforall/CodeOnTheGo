package com.itsaky.androidide.fragments

import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import android.widget.TextView
import com.itsaky.androidide.R
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.activities.editor.HelpActivity
import com.itsaky.androidide.idetooltips.TooltipCategory
import com.itsaky.androidide.idetooltips.TooltipManager
import com.itsaky.androidide.idetooltips.TooltipTag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.adfa.constants.CONTENT_KEY
import org.adfa.constants.CONTENT_TITLE_KEY

class FindActionDialog(
    private val anchor: View,
    private val context: Context,
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

        findInFileText.apply {
            setOnClickListener {
                popupWindow.dismiss()
                onFindInFileClicked(actionData)
            }
            setOnLongClickListener {
                showTooltip(tag = TooltipTag.EDITOR_TOOLBAR_FIND_IN_FILE)
                true
            }
        }

        findInProjectText.apply {
            setOnClickListener {
                popupWindow.dismiss()
                onFindInProjectClicked(actionData)
            }
            setOnLongClickListener {
                showTooltip(tag = TooltipTag.EDITOR_TOOLBAR_FIND_IN_PROJECT)
                true
            }
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

    private fun showTooltip(tag: String) {
        CoroutineScope(Dispatchers.Main).launch {
            val tooltipItem = TooltipManager.getTooltip(
                context = context,
                category = TooltipCategory.CATEGORY_IDE,
                tag,
            )
            if (tooltipItem != null) {
                TooltipManager.showIDETooltip(
                    context = context,
                    anchorView = anchor,
                    level = 0,
                    tooltipItem = tooltipItem,
                    onHelpLinkClicked = { context, url, title ->
                        val intent =
                            Intent(context, HelpActivity::class.java).apply {
                                putExtra(CONTENT_KEY, url)
                                putExtra(CONTENT_TITLE_KEY, title)
                            }
                        context.startActivity(intent)
                    }
                )
            } else {
                Log.e("EditorHandlerActivity", "Tooltip item $tooltipItem is null")
            }
        }
    }
}
