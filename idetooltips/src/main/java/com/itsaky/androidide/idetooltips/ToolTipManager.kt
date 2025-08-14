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
import android.widget.ImageButton
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.content.ContextCompat.getColor
import com.google.android.material.color.MaterialColors
import com.itsaky.androidide.utils.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object TooltipManager {
    private val TAG = "TooltipManager"
    private val databaseTimestamp: Long = File(Environment.DOC_DB.absolutePath).lastModified()
    private val debugDatabaseFile: File = File(android.os.Environment.getExternalStorageDirectory().toString() +
                                               "/Download/documentation.db")

    val queryTooltip = """
SELECT T.id, T.summary, T.detail
FROM   Tooltips AS T, TooltipCategories as TC
WHERE  T.tooltipCategoryId = TC.id
  AND  TC.category         = ?
  AND  T.tag               = ?
"""

    val queryTooltipButtons = """
SELECT description, uri
FROM   TooltipButtons
WHERE  tooltipId = ?
ORDER  BY buttonNumberId
"""

    suspend fun getTooltip(context: Context, category: String, tag: String): IDETooltipItem? {
        return withContext(Dispatchers.IO) {
            var dbPath = Environment.DOC_DB.absolutePath

            // TODO: The debug database code should only exist in a debug build. --DS, 30-Jul-2025
            val debugDatabaseTimestamp = if (debugDatabaseFile.exists()) debugDatabaseFile.lastModified() else -1L

            if (debugDatabaseTimestamp > databaseTimestamp) {
                // Switch to the debug database.
                dbPath = debugDatabaseFile.absolutePath
            }

            try {
                val db = SQLiteDatabase.openDatabase(dbPath, null, SQLiteDatabase.OPEN_READONLY)

                val cursor = db.rawQuery(queryTooltip, arrayOf(category, tag))
                
                when (cursor.count) {
                    0 -> throw NoTooltipFoundException(category, tag)
                    1 -> { /* Expected case, continue processing */ }
                    else -> throw DatabaseCorruptionException(
                        "Multiple tooltips found for category='$category', tag='$tag' (found ${cursor.count} rows). " +
                        "This indicates database corruption - each category/tag combination should be unique."
                    )
                }

                cursor.moveToFirst()

                val id      = cursor.getInt(0)
                val summary = cursor.getString(1)
                val detail  = cursor.getString(2)

                val buttonCursor = db.rawQuery(queryTooltipButtons, arrayOf(id.toString()))
                    
                val buttons = ArrayList<Pair<String, String>>()
                while (buttonCursor.moveToNext()) {
                    buttons.add(Pair(buttonCursor.getString(0), buttonCursor.getString(1)))
                }

                Log.d(TAG, "Retrieved ${buttons.size} buttons. They are $buttons.")
                    
                buttonCursor.close()
                cursor.close()
                db.close()
                    
                IDETooltipItem(category, tag, summary, detail, buttons)
                
            } catch (e: Exception) {
                Log.e(TAG, "Error getting tooltip for category='$category', tag='$tag': ${e.message}")
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

// TODO: The color string below should be externalized so our documentation team can control them, for example with CSS. --DS, 30-Jul-2025
        fun Int.toHexColor(): String = String.format("#%06X", 0xFFFFFF and this)
        val hexColor = textColor.toHexColor()

        var tooltipHtmlContent = when (level) {
            0 -> tooltipItem.summary
            1 -> {
                val detailContent = if (tooltipItem.detail.isNotBlank()) tooltipItem.detail else ""
                if (tooltipItem.buttons.isNotEmpty()) {
                    val linksHtml = tooltipItem.buttons.joinToString("<br>") { (label, url) ->
                        context.getString(R.string.tooltip_links_html_template, url, label)
                    }
                    if (detailContent.isNotBlank()) {
                        "$detailContent<br><br>$linksHtml"
                    } else {
                        linksHtml
                    }
                } else {
                    detailContent
                }
            }
            else -> ""
        }

        Log.d(TAG, "Level: $level, Content: ${tooltipHtmlContent.take(100)}...")

        val styledHtml = context.getString(R.string.tooltip_html_template, hexColor, tooltipHtmlContent)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                url?.let { clickedUrl ->
                    popupWindow.dismiss()
                    // Find the button label for this URL to use as title
                    val buttonLabel = tooltipItem.buttons.find { it.second == clickedUrl }?.first ?: tooltipItem.tooltipTag
                    onActionButtonClick(popupWindow, Pair(clickedUrl, buttonLabel))
                }
                return true
            }
        }

        webView.settings.javaScriptEnabled = true
        webView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        webView.loadDataWithBaseURL(null, styledHtml, "text/html", "UTF-8", null)

        seeMore.setOnClickListener {
            popupWindow.dismiss()
            val nextLevel = when {
                level == 0 -> 1
                else -> level + 1
            }
            Log.d(TAG, "See More clicked: level $level -> $nextLevel (detail.isNotBlank=${tooltipItem.detail.isNotBlank()}, buttons.isNotEmpty=${tooltipItem.buttons.isNotEmpty()})")
            onSeeMoreClicked(popupWindow, nextLevel, tooltipItem)
        }
        val shouldShowSeeMore = when {
            level == 0 && (tooltipItem.detail.isNotBlank() || tooltipItem.buttons.isNotEmpty()) -> true
            else -> false
        }
        seeMore.visibility = if (shouldShowSeeMore) View.VISIBLE else View.GONE
        Log.d(TAG, "See More visibility: $shouldShowSeeMore (level=$level, detail.isNotBlank=${tooltipItem.detail.isNotBlank()}, buttons.isNotEmpty=${tooltipItem.buttons.isNotEmpty()})")

        val transparentColor = getColor(context, android.R.color.transparent)
        popupWindow.setBackgroundDrawable(ColorDrawable(transparentColor))
        popupView.setBackgroundResource(R.drawable.idetooltip_popup_background)


        popupWindow.isFocusable = true
        popupWindow.isOutsideTouchable = true
        popupWindow.showAtLocation(anchorView, Gravity.CENTER, 0, 0)
        val infoButton = popupView.findViewById<ImageButton>(R.id.icon_info)
        infoButton.setOnClickListener {
            onInfoButtonClicked(context, popupWindow, tooltipItem)
        }
    }
    /**
     * Handles the click on the info icon in the tooltip.
     */
    private fun onInfoButtonClicked(context: Context, popupWindow: PopupWindow, tooltip: IDETooltipItem) {
        popupWindow.dismiss()

        val metadata = """
        <b>ID:</b> "${tooltip.tooltipTag}"<br/>
        <b>Raw Summary:</b> "${android.text.Html.escapeHtml(tooltip.summary)}"<br/>
        <b>Raw Detail:</b> "${android.text.Html.escapeHtml(tooltip.detail)}"<br/>
        <b>Buttons:</b> ${tooltip.buttons.joinToString { "\"${it.first} → ${it.second}\"" }}<br/>
        """.trimIndent()

        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle("Tooltip Debug Info")
            .setMessage(android.text.Html.fromHtml(metadata, android.text.Html.FROM_HTML_MODE_LEGACY))
            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                dialog.dismiss()
            }
            .setCancelable(true) // Allow dismissing by tapping outside
            .show()
    }
}

