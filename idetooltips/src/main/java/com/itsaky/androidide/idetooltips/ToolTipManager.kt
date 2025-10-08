package com.itsaky.androidide.idetooltips

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
import android.view.animation.AnimationUtils
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.PopupWindow
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat.getColor
import com.google.android.material.color.MaterialColors
import com.itsaky.androidide.activities.editor.HelpActivity
import com.itsaky.androidide.buildinfo.BuildInfo
import com.itsaky.androidide.utils.Environment
import com.itsaky.androidide.utils.FeedbackManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.adfa.constants.CONTENT_KEY
import org.adfa.constants.CONTENT_TITLE_KEY
import java.io.File


object TooltipManager {
    private const val TAG = "TooltipManager"
    private val databaseTimestamp: Long = File(Environment.DOC_DB.absolutePath).lastModified()
    private val debugDatabaseFile: File = File(android.os.Environment.getExternalStorageDirectory().toString() +
            "/Download/documentation.db")

    private const val QUERY_TOOLTIP = """
        SELECT T.rowid, T.id, T.summary, T.detail
        FROM Tooltips AS T, TooltipCategories AS TC
        WHERE T.categoryId = TC.id
          AND T.tag = ?
          AND TC.category = ?
    """


    private const val QUERY_TOOLTIP_BUTTONS = """
        SELECT description, uri
        FROM TooltipButtons
        WHERE tooltipId = ?
        ORDER BY buttonNumberId
    """

    private const val QUERY_LAST_CHANGE = """
        SELECT changeTime, who
        FROM LastChange
        WHERE documentationSet = 'wholedb'
    """

    suspend fun getTooltip(context: Context, category: String, tag: String): IDETooltipItem? {
        Log.d(TAG, "In getTooltip() for category='$category', tag='$tag'.")

        return withContext(Dispatchers.IO) {
            var dbPath = Environment.DOC_DB.absolutePath

            // TODO: The debug database code should only exist in a debug build. --DS, 30-Jul-2025
            val debugDatabaseTimestamp =
                if (debugDatabaseFile.exists()) debugDatabaseFile.lastModified() else -1L

            if (debugDatabaseTimestamp > databaseTimestamp) {
                // Switch to the debug database.
                dbPath = debugDatabaseFile.absolutePath
            }

            var lastChange = "n/a"
            var rowId = -1
            var tooltipId = -1
            var summary = "n/a"
            var detail = "n/a"
            var buttons: ArrayList<Pair<String, String>> = ArrayList<Pair<String, String>>()

            try {
                val db = SQLiteDatabase.openDatabase(dbPath, null, SQLiteDatabase.OPEN_READONLY)

                var cursor = db.rawQuery(QUERY_LAST_CHANGE, arrayOf())
                cursor.moveToFirst()

                lastChange = "${cursor.getString(0)} ${cursor.getString(1)}"

                Log.d(TAG, "last change is '${lastChange}'.")

                cursor = db.rawQuery(QUERY_TOOLTIP, arrayOf(tag, category))

                when (cursor.count) {
                    0 -> throw NoTooltipFoundException(category, tag)
                    1 -> { /* Expected case, continue processing */
                    }

                    else -> throw DatabaseCorruptionException(
                        "Multiple tooltips found for category='$category', tag='$tag' (found ${cursor.count} rows). " +
                                "Each category/tag combination should be unique."
                    )
                }

                cursor.moveToFirst()

                rowId = cursor.getInt(0)
                tooltipId = cursor.getInt(1)
                summary = cursor.getString(2)
                detail = cursor.getString(3)

                cursor = db.rawQuery(QUERY_TOOLTIP_BUTTONS, arrayOf(tooltipId.toString()))

                while (cursor.moveToNext()) {
                    buttons.add(
                        Pair(
                            cursor.getString(0),
                            "http://localhost:6174/" + cursor.getString(1)
                        )
                    )
                }

                Log.d(
                    TAG,
                    "For tooltip ${tooltipId}, retrieved ${buttons.size} buttons. They are $buttons."
                )

                cursor.close()
                db.close()

            } catch (e: Exception) {
                Log.e(
                    TAG,
                    "Error getting tooltip for category='$category', tag='$tag': ${e.message}"
                )
            }

            IDETooltipItem(rowId, tooltipId, category, tag, summary, detail, buttons, lastChange)
        }
    }

    // Displays a tooltip in a particular context (An Activity, Fragment, Dialog etc)
    fun showTooltip(context: Context, anchorView: View, tag: String) {
        CoroutineScope(Dispatchers.Main).launch {
            val tooltipItem = getTooltip(
                context,
                TooltipCategory.CATEGORY_IDE,
                tag,
            )
            if (tooltipItem != null) {
                showIDETooltip(
                    context = context,
                    anchorView = anchorView,
                    level = 0,
                    tooltipItem = tooltipItem,
                    onHelpLinkClicked = { context, url, title ->
                        val intent =
                            Intent(context, HelpActivity::class.java).apply {
                                putExtra(CONTENT_KEY, url)
                                putExtra(CONTENT_TITLE_KEY, title)
                                if (context !is android.app.Activity) {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                            }
                        context.startActivity(intent)
                    }
                )
            } else {
                Log.e("TooltipManager", "Tooltip item $tooltipItem is null")
            }
        }
    }

    // Displays a tooltip in a particular context with a specific category
    fun showTooltip(context: Context, anchorView: View, category: String, tag: String) {
        CoroutineScope(Dispatchers.Main).launch {
            val tooltipItem = getTooltip(
                context,
                category,
                tag,
            )
            if (tooltipItem != null) {
                showIDETooltip(
                    context = context,
                    anchorView = anchorView,
                    level = 0,
                    tooltipItem = tooltipItem,
                    onHelpLinkClicked = { context, url, title ->
                        val intent =
                            Intent(context, HelpActivity::class.java).apply {
                                putExtra(CONTENT_KEY, url)
                                putExtra(CONTENT_TITLE_KEY, title)
                                if (context !is android.app.Activity) {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                            }
                        context.startActivity(intent)
                    }
                )
            } else {
                Log.e("TooltipManager", "Tooltip item $tooltipItem is null")
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

        val styledHtml =
            context.getString(R.string.tooltip_html_template, hexColor, tooltipHtmlContent)

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
            Log.d(
                TAG,
                "See More clicked: level $level -> $nextLevel (detail.isNotBlank=${tooltipItem.detail.isNotBlank()}, buttons.isNotEmpty=${tooltipItem.buttons.isNotEmpty()})"
            )
            onSeeMoreClicked(popupWindow, nextLevel, tooltipItem)
        }
        val shouldShowSeeMore = when {
            level == 0 && (tooltipItem.detail.isNotBlank() || tooltipItem.buttons.isNotEmpty()) -> true
            else -> false
        }
        seeMore.visibility = if (shouldShowSeeMore) View.VISIBLE else View.GONE
        Log.d(
            TAG,
            "See More visibility: $shouldShowSeeMore (level=$level, detail.isNotBlank=${tooltipItem.detail.isNotBlank()}, buttons.isNotEmpty=${tooltipItem.buttons.isNotEmpty()})"
        )

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

        val feedbackButton = popupView.findViewById<ImageButton>(R.id.feedback_button)
        val pulseAnimation = AnimationUtils.loadAnimation(context, R.anim.pulse_animation)
        feedbackButton.startAnimation(pulseAnimation)

        feedbackButton.setOnClickListener {
            onFeedbackButtonClicked(context, popupWindow, tooltipItem)
        }
    }

    /**
     * Handles the click on the info icon in the tooltip.
     */
    private fun onInfoButtonClicked(
        context: Context,
        popupWindow: PopupWindow,
        tooltip: IDETooltipItem
    ) {
        popupWindow.dismiss()

        val metadata = """
        <b>Version</b> <small>'${tooltip.lastChange}'</small><br/>
        <b>Row:</b> ${tooltip.rowId}<br/>
        <b>ID:</b> ${tooltip.id}<br/>
        <b>Category:</b> '${tooltip.category}'<br/>
        <b>Tag:</b> '${tooltip.tag}'<br/>
        <b>Raw Summary:</b> '${Html.escapeHtml(tooltip.summary)}'<br/>
        <b>Raw Detail:</b> '${Html.escapeHtml(tooltip.detail)}'<br/>
        <b>Buttons:</b> ${tooltip.buttons.joinToString { "'${it.first} → ${it.second}'" }}<br/>
        """.trimIndent()

        AlertDialog.Builder(context)
            .setTitle("Tooltip Debug Info")
            .setMessage(
                Html.fromHtml(
                    metadata,
                    Html.FROM_HTML_MODE_LEGACY
                )
            )
            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                dialog.dismiss()
            }
            .setCancelable(true) // Allow dismissing by tapping outside
            .show()
    }

    private fun onFeedbackButtonClicked(
        context: Context,
        popupWindow: PopupWindow,
        tooltip: IDETooltipItem
    ) {
        popupWindow.dismiss()

        val feedbackMetadata = buildTooltipFeedbackMetadata(tooltip)

        FeedbackManager.sendFeedbackWithScreenshot(
            context = context,
            customSubject = "Tooltip Feedback - ${tooltip.tag}",
            metadata = feedbackMetadata,
            includeScreenshot = true,
            appVersion = BuildInfo.VERSION_NAME_SIMPLE
        )
    }


    private fun buildTooltipFeedbackMetadata(tooltip: IDETooltipItem): String {
        return """
            Please describe your feedback above this line.

            --- Tooltip Information ---
            Version: '${tooltip.lastChange}'
            Row: ${tooltip.rowId}
            ID: ${tooltip.id}
            Category: '${tooltip.category}'
            Tag: '${tooltip.tag}'

            Summary: '${tooltip.summary}'
            Detail: '${tooltip.detail}'

            Buttons:
            ${tooltip.buttons.joinToString("\n") { " - ${it.first}: ${it.second}" }}

            ---
        """.trimIndent()
    }

}
