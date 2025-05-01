/*
 * This file is part of AndroidIDE.
 *
 * AndroidIDE is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AndroidIDE is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.utils

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentTransaction
import com.google.android.material.color.MaterialColors
import com.itsaky.androidide.R
import com.itsaky.androidide.activities.MainActivity
import com.itsaky.androidide.fragments.IDETooltipWebviewFragment
import com.itsaky.androidide.fragments.MainFragment
import com.itsaky.androidide.idetooltips.IDETooltipDatabase
import com.itsaky.androidide.idetooltips.IDETooltipItem
import io.github.rosemoe.sora.widget.CodeEditor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object TooltipUtils {
    private val mainActivity: MainActivity?
        get() = MainActivity.getInstance()


    /**
     * This displays a webpage as the 3rd level tooltip
     *
     * @param   context callers context
     * @param   url    the url as specified in the tooltips database
     * @return  none
     */
    fun showWebPage(context: Context, url: String) {
        val currentActivity = mainActivity ?: return
        val transaction: FragmentTransaction =
            currentActivity.supportFragmentManager.beginTransaction().addToBackStack("WebView")
        val fragment = IDETooltipWebviewFragment()
        val bundle = Bundle()
        bundle.putString(MainFragment.KEY_TOOLTIP_URL, url)
        fragment.arguments = bundle
        transaction.replace(R.id.fragment_containers_parent, fragment)
        transaction.commitAllowingStateLoss()
    }

    /**
     * Shows a tooltip anchored to a generic view.
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun showIDETooltip(
        context: Context,
        anchorView: View,
        level: Int,
        tooltipItem: IDETooltipItem
    ) {

        val onActionButtonClickAction: (PopupWindow, String) -> Unit = { popupWindow, url ->
            popupWindow.dismiss()
            showWebPage(context, url) // Call showWebPage
        }

        // Call the common internal function
        setupAndShowTooltipPopup(
            context = context,
            anchorView = anchorView,
            level = level,
            tooltipItem = tooltipItem,
            onActionButtonClick = onActionButtonClickAction
        )
    }

    /**
     * Shows a tooltip anchored to the CodeEditor.
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun showEditorTooltip(
        context: Context,
        editor: CodeEditor,
        level: Int,
        tooltipItem: IDETooltipItem?,
        block: (htmlString: String) -> Unit // Action for buttons
    ) {
        tooltipItem?.let { item -> // Only proceed if tooltipItem is not null


            val onActionButtonClickAction: (PopupWindow, String) -> Unit = { popupWindow, url ->
                popupWindow.dismiss()
                block.invoke(url) // Call the provided block
            }

            // Call the common internal function
            setupAndShowTooltipPopup(
                context = context,
                anchorView = editor, // Use editor as anchor
                level = level,
                tooltipItem = item,
                onActionButtonClick = onActionButtonClickAction
            )
        }
    }

    /**
     * Internal helper function to create, configure, and show the tooltip PopupWindow.
     * Contains the logic common to both showIDETooltip and showEditorTooltip.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun setupAndShowTooltipPopup(
        context: Context,
        anchorView: View,
        level: Int,
        tooltipItem: IDETooltipItem,
        onActionButtonClick: (popupWindow: PopupWindow, url: String) -> Unit
    ) {
        val inflater = LayoutInflater.from(context)
        val popupView = inflater.inflate(R.layout.ide_tooltip_window, null)
        val popupWindow = PopupWindow(
            popupView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val seeMore = popupView.findViewById<TextView>(R.id.see_more)
        val webView = popupView.findViewById<WebView>(R.id.webview)

        val textColor = MaterialColors.getColor(
            context,
            com.google.android.material.R.attr.colorOnSurface,
            "Color attribute not found in theme"
        )

        fun Int.toHexColor(): String = String.format("#%06X", 0xFFFFFF and this)
        val hexColor = textColor.toHexColor()

        var tooltipHtmlContent = when (level) {
            0 -> tooltipItem.summary
            1 -> "${tooltipItem.summary}<br>${tooltipItem.detail}"
            else -> ""
        }

        val isLastLevel = (level == 0 && tooltipItem.detail.isBlank()) || level == 1
        if (isLastLevel && tooltipItem.buttons.isNotEmpty()) {
            val linksHtml = tooltipItem.buttons.joinToString("<br>") { (label, url) ->
                """<a href="$url" style="color:#00be00;text-decoration:underline;">$label</a>"""
            }
            tooltipHtmlContent += "<br><br>$linksHtml"
        }

        // Full HTML
        val styledHtml = """
        <!DOCTYPE html>
        <html>
            <head>
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <style>
                    body {
                        margin: 0;
                        padding: 10px;
                        word-wrap: break-word;
                        color: $hexColor;
                    }
                    a {
                        color: #00be00;
                        text-decoration: underline;
                    }
                </style>
            </head>
            <body>
                $tooltipHtmlContent
            </body>
        </html>
        """.trimIndent()

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                url?.let {
                    popupWindow.dismiss()
                    onActionButtonClick(popupWindow, it)
                }
                return true
            }
        }
        webView.settings.javaScriptEnabled = true
        webView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        webView.loadDataWithBaseURL(null, styledHtml, "text/html", "UTF-8", null)

        seeMore.setOnClickListener {
            popupWindow.dismiss()
            showIDETooltip(context, anchorView, level + 1, tooltipItem)
        }
        seeMore.visibility =
            if (level == 0 && tooltipItem.detail.isNotBlank()) View.VISIBLE else View.GONE

        val transparentColor = ContextCompat.getColor(context, android.R.color.transparent)
        popupWindow.setBackgroundDrawable(ColorDrawable(transparentColor))
        popupView.setBackgroundResource(R.drawable.idetooltip_popup_background)
        popupWindow.isFocusable = true
        popupWindow.isOutsideTouchable = true
        popupWindow.showAtLocation(anchorView, Gravity.CENTER, 0, 0)
    }

    /**
     * Dumps tooltip database content to Logcat for debugging.
     */
    suspend fun dumpDatabase(context: Context, database: IDETooltipDatabase) {
        // No changes needed here
        CoroutineScope(Dispatchers.IO).launch {
            val records =
                IDETooltipDatabase.getDatabase(context).idetooltipDao().getTooltipItems()
            withContext(Dispatchers.Main) {
                if(records.isEmpty()) {
                    Log.d("DumpIDEDatabase", "No records found in IDETooltipDatabase.")
                } else {
                    for(item: IDETooltipItem in records) {
                        Log.d(
                            "DumpIDEDatabase",
                            "tag = ${item.tooltipTag}\n\tsummary = ${item.summary}\n\tdetail = ${item.detail}\n\tbuttons = ${item.buttons}"
                        )
                    }
                }
            }
        }
    }
}