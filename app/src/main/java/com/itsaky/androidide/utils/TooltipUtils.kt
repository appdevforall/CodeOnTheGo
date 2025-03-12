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
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.fragment.app.FragmentTransaction
import com.itsaky.androidide.R
import com.itsaky.androidide.activities.MainActivity
import com.itsaky.androidide.fragments.IDETooltipWebviewFragment
import com.itsaky.androidide.fragments.MainFragment
import com.itsaky.androidide.idetooltips.IDETooltipDatabase
import com.itsaky.androidide.idetooltips.IDETooltipItem
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
        val transaction: FragmentTransaction =
            mainActivity?.supportFragmentManager!!.beginTransaction().addToBackStack("WebView")
        val fragment = IDETooltipWebviewFragment()
        val bundle = Bundle()
        bundle.putString(MainFragment.KEY_TOOLTIP_URL, url)
        fragment.arguments = bundle
        transaction.replace(R.id.fragment_containers_parent, fragment)
        transaction.commitAllowingStateLoss()
    }

    @SuppressLint("SetJavaScriptEnabled", "SetTextI18n")
    fun showIDETooltip(
        context: Context,
        view: View,
        level: Int,
        detail: String,
        summary: String,
        buttons: ArrayList<Pair<String, String>>,
        block: (htmlString: String) -> Unit
    ) {
        val popupView = mainActivity?.layoutInflater!!.inflate(R.layout.ide_tooltip_window, null)
        val popupWindow = PopupWindow(
            popupView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        // Inflate the PopupWindow layout
        val seeMoreText = popupView.findViewById<TextView>(R.id.see_more_text)
        val tooltip = when (level) {
            0 -> summary
            1 -> "$summary<br>$detail"
            else -> ""
        }

        seeMoreText.text = "+ See more"
        seeMoreText.setTextColor(Color.parseColor("#00be00"))
        seeMoreText.visibility = View.VISIBLE
        seeMoreText.setOnClickListener {
            popupWindow.dismiss()
            showIDETooltip(
                context,
                view,
                level + 1,
                detail,
                summary,
                buttons
            ) { url ->
                showWebPage(context, url)
            }
        }

        val webView = popupView.findViewById<WebView>(R.id.webview)
        webView.webViewClient = WebViewClient()
        webView.settings.javaScriptEnabled = true
        webView.loadData(tooltip, "text/html", "UTF-8")

        popupWindow.setBackgroundDrawable(ColorDrawable(mainActivity?.getColor(android.R.color.transparent)!!))
        popupView.setBackgroundResource(R.drawable.idetooltip_popup_background)

        if ((level == 0 && detail.isBlank()) || level == 1) {
            seeMoreText.visibility = View.GONE

            val rootLayout = popupView.findViewById<ConstraintLayout>(R.id.root_layout)
            var previousLinkView: TextView? = null

            for ((index, buttonPair) in buttons.withIndex()) {
                val isLastLink = index == buttons.size - 1
                val linkTextView = TextView(context).apply {
                    text = buttonPair.first
                    setTextColor(Color.parseColor("#00be00"))
                    paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
                    setOnClickListener {
                        val url: String = buttonPair.second
                        popupWindow.dismiss()
                        block(url)
                    }
                    layoutParams = ConstraintLayout.LayoutParams(
                        ConstraintLayout.LayoutParams.WRAP_CONTENT,
                        ConstraintLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                        if (previousLinkView == null) {
                            topToBottom = R.id.webview
                        } else {
                            topToBottom = previousLinkView!!.id
                        }
                    }
                    id = View.generateViewId()

                    if (isLastLink) {
                        setPadding(28.dpToPx(context), 0, 0, 40)
                    } else {
                        setPadding(28.dpToPx(context), 0, 0, 0)
                    }
                }
                rootLayout.addView(linkTextView)
                previousLinkView = linkTextView
            }
        }

        popupWindow.isFocusable = true
        popupWindow.isOutsideTouchable = true
        popupWindow.showAtLocation(view, Gravity.CENTER, 0, 0)
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

    private fun Int.dpToPx(context: Context): Int {
        return (this * context.resources.displayMetrics.density).toInt()
    }
}