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

package com.itsaky.androidide.utils

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.PopupWindow
import androidx.annotation.RequiresApi
import androidx.fragment.app.FragmentTransaction
import com.android.aaptcompiler.Visibility
import com.google.android.material.floatingactionbutton.FloatingActionButton
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
import java.lang.reflect.Method

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
        val transaction: FragmentTransaction =
            mainActivity?.supportFragmentManager!!.beginTransaction().addToBackStack("WebView")
        val fragment = IDETooltipWebviewFragment()
        val bundle = Bundle()
        bundle.putString(MainFragment.KEY_TOOLTIP_URL, url)
        fragment.arguments = bundle
        transaction.replace(R.id.fragment_containers_parent, fragment)
        transaction.commitAllowingStateLoss()
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun showIDETooltip(
        context: Context,
        view: View,
        level: Int,
        detail: String,
        summary: String,
        buttons: ArrayList<Pair<String, String>>
    ) {
        val popupView = mainActivity?.layoutInflater!!.inflate(R.layout.ide_tooltip_window, null)
        val popupWindow = PopupWindow(
            popupView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        // Inflate the PopupWindow layout
        val buttonId = listOf(R.id.button1, R.id.button2, R.id.button3)
        val fab = popupView.findViewById<Button>(R.id.fab)
        val tooltip = when (level) {
            0 -> summary
            1 -> "$summary<br>$detail"
            else -> ""
        }

        //Dismiss the old PopupWindow
        fab.setOnClickListener {
            popupWindow.dismiss()
            showIDETooltip(context, view, level + 1, detail, summary, buttons)
        }

        mainActivity?.getColor(android.R.color.holo_blue_light)
            ?.let { popupView.setBackgroundColor(it) }
        //start with all the buttons gone and the FAB visible
        for (index in 0..2) {
            popupView.findViewById<Button>(buttonId[index]).visibility = View.GONE
        }
        fab.visibility = View.VISIBLE


        val htmlString =
            "<head><style type=\"text/css\"> html, body { width:100%; height: 100%; margin: 0px; padding: 0px; }" +
                    "</style></head><body>$tooltip</body>"

        // Initialize the WebView
        val webView = popupView.findViewById<WebView>(R.id.webview)
        webView.webViewClient = WebViewClient() // Ensure links open within the WebView
        webView.settings.javaScriptEnabled = true // Enable JavaScript if needed
        webView.loadData(tooltip, "text/html", "UTF-8")
        // Set the background to match the theme
        popupWindow.setBackgroundDrawable(ColorDrawable(mainActivity?.getColor(android.R.color.transparent)!!))

        // Optional: Set up a border or padding if needed (you'll need to define this in your popup layout XML)
        // Set a theme-aware background, depending on your design
        popupView.setBackgroundResource(R.drawable.idetooltip_popup_background)

        if ((level == 0 && detail.isBlank()) || level == 1) {
            fab.visibility = View.GONE
            if (buttons.size > 0) {
                var buttonIndex = 0
                for (buttonPair: Pair<String, String> in buttons) {
                    val id = buttonId[buttonIndex++]
                    val button = popupView.findViewById<Button>(id)
                    button?.text = buttonPair.first
                    button?.visibility = View.VISIBLE
                    button?.tag = buttonPair.second
                    button?.setOnClickListener(View.OnClickListener { view ->
                        val btn = view as Button
                        val url: String = btn.tag.toString()
                        popupWindow.dismiss()
                        showWebPage(context, url)
                    })
                }
            }
        }

        // Show the popup window
        popupWindow.isFocusable = true

        // Optional: For dismissing the popup when clicking outside
        popupWindow.isOutsideTouchable = true

        // Show the PopupWindow
        popupWindow.showAtLocation(
            view,
            Gravity.CENTER,
            0,
            0 /*anchorView.x.toInt(), anchorView.y.toInt()*/
        )
    }

    /**
     * showEditorToolTip
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun showEditorTooltip(
        context: Context,
        editor: CodeEditor,
        level: Int,
        tooltip: IDETooltipItem?,
        block: (htmlString: String) -> Unit
    ) {
        val inflater = LayoutInflater.from(context)
        val popupView = inflater.inflate(R.layout.ide_tooltip_window, null)
        val popupWindow = PopupWindow(
            popupView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        var htmlString: String = ""

        tooltip?.let {
            val mainActivity: MainActivity? = MainActivity.getInstance()

            // Inflate the PopupWindow layout
            val buttonId = listOf(R.id.button1, R.id.button2, R.id.button3)
            val fab = popupView.findViewById<Button>(R.id.fab)
            val tooltipText = when (level) {
                0 -> tooltip.summary
                1 -> tooltip.summary + "<br/>" + tooltip.detail
                else -> ""
            }

            fab.setOnClickListener {
                popupWindow.dismiss()
                showEditorTooltip(context, editor, level + 1, tooltip, block)
            }


            fab.visibility = View.VISIBLE

            val webView: WebView = popupView.findViewById(R.id.webview)
            webView.loadData(tooltipText, "text/html", "utf-8")
            webView.webViewClient = WebViewClient() // Ensure links open within the WebView
            webView.settings.javaScriptEnabled = true // Enable JavaScript if needed

            // Set the background to match the them
            mainActivity?.getColor(android.R.color.holo_blue_light)
                ?.let { popupView.setBackgroundColor(it) }

            // Optional: Set up a border or padding if needed (you'll need to define this in your popup layout XML)
            // Set a theme-aware background, depending on your design
            popupView.setBackgroundResource(R.drawable.idetooltip_popup_background)

        if ((level == 0 && tooltip.detail.isBlank()) || level == 1) {
            fab.visibility = View.GONE
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
                        val url: String = btn.tag.toString()
                        popupWindow.dismiss()
                        block.invoke(url)
                    })
                }
            }
        }

        popupWindow.isOutsideTouchable = true
        popupWindow.isFocusable = true

        popupWindow.showAtLocation(editor, Gravity.CENTER, 0, 0)
    }
}

suspend fun dumpDatabase(context: Context, database: IDETooltipDatabase) {
    CoroutineScope(Dispatchers.IO).launch {
        val records =
            IDETooltipDatabase.getDatabase(context).idetooltipDao().getTooltipItems()
        withContext(Dispatchers.Main) {
            for (item: IDETooltipItem in records) {
                Log.d(
                    "DumpIDEDatabase",
                    "tag = ${item.tooltipTag}\n\tdetail = ${item.detail}\n\tsummary = ${item.summary}"
                )
            }
        }
    }
}
}
