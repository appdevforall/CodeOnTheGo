package com.itsaky.androidide.idetooltips

import android.annotation.SuppressLint
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.drawable.ColorDrawable
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.content.ContextCompat.getColor
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

object TooltipManager {

    suspend fun getTooltip(context: Context, category: String, tag: String): IDETooltipItem? {
        return withContext(Dispatchers.IO) {
            try {
                val dbPath = "/data/data/com.itsaky.androidide/databases/documentation.db"
                val db = SQLiteDatabase.openDatabase(dbPath, null, SQLiteDatabase.OPEN_READONLY)
                
                val query = """
                    SELECT tooltipCategory, tooltipTag, tooltipSummary, tooltipDetail, tooltipButtons
                    FROM ide_tooltip_table
                    WHERE tooltipCategory = ? AND tooltipTag = ?
                    LIMIT 1
                """
                
                val cursor = db.rawQuery(query, arrayOf(category, tag))
                
                if (cursor.moveToFirst()) {
                    val tooltipCategory = cursor.getString(cursor.getColumnIndexOrThrow("tooltipCategory"))
                    val tooltipTag = cursor.getString(cursor.getColumnIndexOrThrow("tooltipTag"))
                    val summary = cursor.getString(cursor.getColumnIndexOrThrow("tooltipSummary"))
                    val detail = cursor.getString(cursor.getColumnIndexOrThrow("tooltipDetail"))
                    val buttonsJson = cursor.getString(cursor.getColumnIndexOrThrow("tooltipButtons"))
                    
                    // Parse buttons JSON
                    val buttons = ArrayList<Pair<String, String>>()
                    try {
                        val jsonArray = JSONArray(buttonsJson)
                        for (i in 0 until jsonArray.length()) {
                            val buttonObj = jsonArray.getJSONObject(i)
                            val label = buttonObj.getString("label")
                            val url = buttonObj.getString("url")
                            buttons.add(Pair(label, url))
                        }
                    } catch (e: Exception) {
                        Log.e("TooltipManager", "Error parsing buttons JSON: ${e.message}")
                    }
                    
                    cursor.close()
                    db.close()
                    
                    IDETooltipItem(tooltipCategory, tooltipTag, summary, detail, buttons)
                } else {
                    cursor.close()
                    db.close()
                    null
                }
                
            } catch (e: Exception) {
                Log.e("TooltipManager", "Error getting tooltip for category=$category, tag=$tag: ${e.message}")
                null
            }
        }
    }

    /**
     * Shows a tooltip anchored to a generic view.
     */
    fun showIDETooltip(
        context: Context,
        anchorView: View,
        level: Int,
        tooltipItem: IDETooltipItem,
        onHelpLinkClicked: (context: Context, url: String, title: String) -> Unit
    ) {
        setupAndShowTooltipPopup(
            context = context,
            anchorView = anchorView,
            level = level,
            tooltipItem = tooltipItem,
            onActionButtonClick = { popupWindow, urlContent ->
                popupWindow.dismiss()
                onHelpLinkClicked(context, urlContent.first, urlContent.second)
            },
            onSeeMoreClicked = { popupWindow, nextLevel, item ->
                popupWindow.dismiss()
                showIDETooltip(context, anchorView, nextLevel, item, onHelpLinkClicked)
            }
        )
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
        onActionButtonClick: (popupWindow: PopupWindow, url: Pair<String, String>) -> Unit,
        onSeeMoreClicked: (popupWindow: PopupWindow, nextLevel: Int, tooltipItem: IDETooltipItem) -> Unit,
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
                """<a href="$url" style="color:#233490;text-decoration:underline;">$label</a>"""
            }
            tooltipHtmlContent += "<br><br>$linksHtml"
        }

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
                     a{
                        color: #233490;
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
                    onActionButtonClick(popupWindow, Pair(it, tooltipItem.tooltipTag))
                }
                return true
            }
        }

        webView.settings.javaScriptEnabled = true
        webView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        webView.loadDataWithBaseURL(null, styledHtml, "text/html", "UTF-8", null)

        seeMore.setOnClickListener {
            popupWindow.dismiss()
            onSeeMoreClicked(popupWindow, level + 1, tooltipItem)
        }
        seeMore.visibility =
            if (level == 0 && tooltipItem.detail.isNotBlank()) View.VISIBLE else View.GONE

        val transparentColor = getColor(context, android.R.color.transparent)
        popupWindow.setBackgroundDrawable(ColorDrawable(transparentColor))
        popupView.setBackgroundResource(R.drawable.idetooltip_popup_background)


        popupWindow.isFocusable = true
        popupWindow.isOutsideTouchable = true
        popupWindow.showAtLocation(anchorView, Gravity.CENTER, 0, 0)
    }

}

