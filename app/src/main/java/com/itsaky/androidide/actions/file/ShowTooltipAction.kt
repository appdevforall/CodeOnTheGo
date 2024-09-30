/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.actions.file

import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.PopupWindow
import androidx.core.content.ContextCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.itsaky.androidide.R
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.ActionItem
import com.itsaky.androidide.actions.EditorRelatedAction
import com.itsaky.androidide.activities.MainActivity
import com.itsaky.androidide.idetooltips.IDETooltipItem
import com.itsaky.androidide.utils.TooltipUtils.showWebPage
import io.github.rosemoe.sora.widget.CodeEditor

class ShowTooltipAction(private val context: Context, override val order: Int) :
    EditorRelatedAction() {
    override val id: String = "ide.editor.code.text.format"
    override var location: ActionItem.Location = ActionItem.Location.EDITOR_TEXT_ACTIONS
    var htmlString: String = ""

    init {
        label = context.getString(R.string.title_show_tooltip)
        icon = ContextCompat.getDrawable(context, R.drawable.ic_docs)
    }

    override suspend fun execAction(data: ActionData): Any {
        val editor = data.getEditor()!!
        val cursor = editor.text.cursor
        val activity = data.getActivity()
        val word = editor.text.substring(cursor.left, cursor.right)
        if (cursor.isSelected) {
            activity?.getTooltipData(word)?.let { tooltipData ->
                showTooltip(
                    editor,
                    0,
                    tooltipData,
                ) { activity.openFAQActivity(htmlString) }
            }
        }

        return true
    }

    /**
     * showToolTip
     */
    private fun showTooltip(
        editor: CodeEditor,
        level : Int,
        tooltip: IDETooltipItem?,
        block: () -> Unit
    ) {
        val inflater = LayoutInflater.from(context)
        val popupView = inflater.inflate(R.layout.ide_tooltip_window, null)
        val popupWindow = PopupWindow(
            popupView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        tooltip?.let {
            val mainActivity: MainActivity? = MainActivity.getInstance()

            // Inflate the PopupWindow layout
            val buttonId = listOf(R.id.button1, R.id.button2, R.id.button3)
            val fab = popupView.findViewById<FloatingActionButton>(R.id.fab)
            val tooltipText = when (level) {
                0 -> tooltip.summary
                1 -> tooltip.summary + "<br/><br/>" + tooltip.detail
                else -> ""
            }

            popupWindow.dismiss()

            fab.setOnClickListener {
                showTooltip(editor, level + 1, tooltip, block)
            }

            fab.visibility = View.VISIBLE

            val webView : WebView = popupView.findViewById(R.id.webview)
            webView.loadData(tooltipText, "text/html", "utf-8")
            webView.webViewClient = WebViewClient() // Ensure links open within the WebView
            webView.settings.javaScriptEnabled = true // Enable JavaScript if needed

            // Set the background to match the them
            mainActivity?.getColor(android.R.color.holo_blue_light)
                ?.let { popupView.setBackgroundColor(it) }

            // Optional: Set up a border or padding if needed (you'll need to define this in your popup layout XML)
            // Set a theme-aware background, depending on your design
            popupView.setBackgroundResource(R.drawable.idetooltip_popup_background)

            if (level == 1) {
                fab.hide()
                if (tooltip.buttons.size > 0) {
                    var buttonIndex = 0
                    for (buttonPair: Pair<String, String> in tooltip.buttons) {
                        val id = buttonId[buttonIndex++]
                        val button = popupView.findViewById<Button>(id)
                        button?.text = buttonPair.first
                        button?.visibility = View.VISIBLE
                        button?.tag = buttonPair.second
                        button?.setOnClickListener(View.OnClickListener { view ->
                            val btn = view as Button
                            val url:String = btn.tag.toString()
                            htmlString = "<p>This is a test"
                            popupWindow.dismiss()
                            block.invoke()
                        })
                    }
                }
            }

            popupWindow.isOutsideTouchable = true
            popupWindow.isFocusable = true

            popupWindow.showAtLocation(editor, Gravity.CENTER, 0, 0)
        }
    }
}
