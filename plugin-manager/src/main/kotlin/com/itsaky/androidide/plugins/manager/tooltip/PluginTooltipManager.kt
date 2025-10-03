package com.itsaky.androidide.plugins.manager.tooltip

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.Html
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
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat.getColor
import com.google.android.material.color.MaterialColors
import com.itsaky.androidide.idetooltips.IDETooltipItem
import com.itsaky.androidide.idetooltips.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import androidx.core.graphics.drawable.toDrawable

/**
 * Manages tooltips specifically for plugins.
 * This is isolated from the main app's TooltipManager to prevent
 * conflicts when the app's documentation database schema changes.
 */
object PluginTooltipManager {
    private const val TAG = "PluginTooltipManager"

    private const val QUERY_TOOLTIP = """
        SELECT T.rowid, T.id, T.summary, T.detail
        FROM PluginTooltips AS T, PluginTooltipCategories AS TC
        WHERE T.categoryId = TC.id
          AND T.tag = ?
          AND TC.category = ?
    """

    private const val QUERY_TOOLTIP_BUTTONS = """
        SELECT description, uri
        FROM PluginTooltipButtons
        WHERE tooltipId = ?
        ORDER BY buttonNumberId
    """

    /**
     * Get the plugin documentation database path.
     */
    private fun getPluginDatabasePath(context: Context): String {
        return context.getDatabasePath("plugin_documentation.db").absolutePath
    }

    /**
     * Retrieve a tooltip from the plugin documentation database.
     */
    suspend fun getTooltip(context: Context, category: String, tag: String): IDETooltipItem? {
        Log.d(TAG, "Getting tooltip for category='$category', tag='$tag'")

        return withContext(Dispatchers.IO) {
            val dbPath = getPluginDatabasePath(context)

            // Check if database exists
            if (!File(dbPath).exists()) {
                Log.w(TAG, "Plugin documentation database does not exist at: $dbPath")
                return@withContext null
            }

            var rowId: Int
            var tooltipId: Int
            var summary: String
            var detail: String
            val buttons = ArrayList<Pair<String, String>>()

            try {
                val db = SQLiteDatabase.openDatabase(dbPath, null, SQLiteDatabase.OPEN_READONLY)

                // Query tooltip
                var cursor = db.rawQuery(QUERY_TOOLTIP, arrayOf(tag, category))

                when (cursor.count) {
                    0 -> {
                        Log.w(TAG, "No tooltip found for category='$category', tag='$tag'")
                        cursor.close()
                        db.close()
                        return@withContext null
                    }
                    1 -> {
                        // Expected case, continue processing
                    }
                    else -> {
                        Log.e(TAG, "Multiple tooltips found for category='$category', tag='$tag' (found ${cursor.count} rows)")
                        cursor.close()
                        db.close()
                        return@withContext null
                    }
                }

                cursor.moveToFirst()

                rowId = cursor.getInt(0)
                tooltipId = cursor.getInt(1)
                summary = cursor.getString(2)
                detail = cursor.getString(3) ?: ""

                cursor.close()

                // Query buttons
                cursor = db.rawQuery(QUERY_TOOLTIP_BUTTONS, arrayOf(tooltipId.toString()))

                while (cursor.moveToNext()) {
                    buttons.add(
                        Pair(
                            cursor.getString(0),
                            "http://localhost:6174/" + cursor.getString(1)
                        )
                    )
                }

                Log.d(TAG, "Retrieved ${buttons.size} buttons for tooltip $tooltipId")

                cursor.close()
                db.close()

            } catch (e: Exception) {
                Log.e(TAG, "Error getting tooltip for category='$category', tag='$tag'", e)
                return@withContext null
            }

            IDETooltipItem(
                rowId = rowId,
                id = tooltipId,
                category = category,
                tag = tag,
                summary = summary,
                detail = detail,
                buttons = buttons,
                lastChange = "Plugin Documentation"
            )
        }
    }

    /**
     * Show a tooltip for a plugin.
     */
    fun showTooltip(context: Context, anchorView: View, category: String, tag: String) {
        CoroutineScope(Dispatchers.Main).launch {
            val tooltipItem = getTooltip(context, category, tag)

            if (tooltipItem != null) {
                showPluginTooltip(
                    context = context,
                    anchorView = anchorView,
                    level = 0,
                    tooltipItem = tooltipItem
                )
            } else {
                Log.e(TAG, "Failed to retrieve tooltip for $category.$tag")
            }
        }
    }

    /**
     * Show the plugin tooltip popup.
     */
    private fun showPluginTooltip(
        context: Context,
        anchorView: View,
        level: Int,
        tooltipItem: IDETooltipItem
    ) {
        setupAndShowTooltipPopup(
            context = context,
            anchorView = anchorView,
            level = level,
            tooltipItem = tooltipItem,
            onActionButtonClick = { popupWindow, urlContent ->
                popupWindow.dismiss()
                // Handle button click - could open documentation or perform action
                Log.d(TAG, "Button clicked: ${urlContent.first}")
            },
            onSeeMoreClicked = { popupWindow, nextLevel, item ->
                popupWindow.dismiss()
                showPluginTooltip(context, anchorView, nextLevel, item)
            }
        )
    }

    /**
     * Internal helper to create and show the tooltip popup window.
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

        val tooltipHtmlContent = when (level) {
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
                    val buttonLabel = tooltipItem.buttons.find { it.second == clickedUrl }?.first
                        ?: tooltipItem.tag
                    onActionButtonClick(popupWindow, Pair(clickedUrl, buttonLabel))
                }
                return true
            }
        }

        webView.settings.javaScriptEnabled = true
        webView.setBackgroundColor(Color.TRANSPARENT)
        webView.loadDataWithBaseURL(null, styledHtml, "text/html", "UTF-8", null)

        seeMore.setOnClickListener {
            popupWindow.dismiss()
            val nextLevel = when {
                level == 0 -> 1
                else -> level + 1
            }
            Log.d(TAG, "See More clicked: level $level -> $nextLevel")
            onSeeMoreClicked(popupWindow, nextLevel, tooltipItem)
        }

        val shouldShowSeeMore = when {
            level == 0 && (tooltipItem.detail.isNotBlank() || tooltipItem.buttons.isNotEmpty()) -> true
            else -> false
        }
        seeMore.visibility = if (shouldShowSeeMore) View.VISIBLE else View.GONE

        val transparentColor = getColor(context, android.R.color.transparent)
        popupWindow.setBackgroundDrawable(transparentColor.toDrawable())
        popupView.setBackgroundResource(R.drawable.idetooltip_popup_background)

        popupWindow.isFocusable = true
        popupWindow.isOutsideTouchable = true
        popupWindow.showAtLocation(anchorView, Gravity.CENTER, 0, 0)
    }

}