package com.itsaky.androidide.activities.editor

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.TextView
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.base.EditorPopupWindow
import java.io.File

/**
 * Renders remote-collaborator presence as small named caret badges floating in the editor,
 * one per (file, peerId). Markers are positioned in content coordinates and track scrolling
 * via [EditorPopupWindow.FEATURE_SCROLL_AS_CONTENT]; the plugin repositions a marker by
 * calling [addMarker] again with a new line/column on each cursor move.
 *
 * All methods must be called on the main thread (the editor view is touched directly).
 */
class PeerPresenceOverlayManager(
    private val editorForFile: (File) -> CodeEditor?,
) {

    private val markers: HashMap<String, HashMap<String, PeerCursorWindow>> = HashMap()

    fun addMarker(
        file: File,
        line: Int,
        column: Int,
        peerId: String,
        peerName: String,
        peerColor: Int,
    ): Boolean {
        val editor = editorForFile(file)
        if (editor == null) {
            Log.d(TAG, "[MARKERS-HOST] no open editor for ${file.name} — cannot show $peerName")
            return false
        }
        val content = editor.text
        if (line !in 0 until content.lineCount) return false
        val safeColumn = column.coerceIn(0, content.getColumnCount(line))
        val byPeer = markers.getOrPut(file.absolutePath) { HashMap() }
        val existing = byPeer[peerId]
        val window = if (existing != null && existing.boundEditor === editor) {
            existing
        } else {
            existing?.dismiss()
            PeerCursorWindow(editor).also { byPeer[peerId] = it }
        }
        window.update(peerName, peerColor, line, safeColumn)
        return true
    }

    fun removeMarker(file: File, peerId: String): Boolean {
        val removed = markers[file.absolutePath]?.remove(peerId) ?: return false
        removed.dismiss()
        return true
    }

    fun clear(file: File) {
        markers.remove(file.absolutePath)?.values?.forEach { it.dismiss() }
    }

    fun clearAll() {
        markers.values.forEach { byPeer -> byPeer.values.forEach { it.dismiss() } }
        markers.clear()
    }

    private companion object {
        const val TAG = "PairTrace"
    }
}

class PeerCursorWindow(
    val boundEditor: CodeEditor,
) : EditorPopupWindow(
    boundEditor,
    FEATURE_SCROLL_AS_CONTENT or FEATURE_SHOW_OUTSIDE_VIEW_ALLOWED,
) {

    private val density = boundEditor.context.resources.displayMetrics.density

    private val label = TextView(boundEditor.context).apply {
        textSize = 11f
        gravity = Gravity.CENTER
        maxLines = 1
        includeFontPadding = false
        val padH = (8 * density).toInt()
        val padV = (3 * density).toInt()
        setPadding(padH, padV, padH, padV)
    }

    init {
        popup.isClippingEnabled = false
        setContentView(label)
    }

    fun update(peerName: String, peerColor: Int, line: Int, column: Int) {
        // getOffset returns the on-screen x. If the caret is past the visible width, add a
        // direction arrow so the badge (clamped to the edge below) signals where the peer is.
        val rawX = boundEditor.getOffset(line, column).toInt()
        label.text = when {
            rawX > boundEditor.width -> "$peerName  →"
            rawX < 0 -> "←  $peerName"
            else -> peerName
        }
        label.setTextColor(contrastingTextColor(peerColor))
        label.background = GradientDrawable().apply {
            setColor(peerColor)
            cornerRadius = 4 * density
        }

        label.measure(
            View.MeasureSpec.makeMeasureSpec(boundEditor.width, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(boundEditor.height, View.MeasureSpec.AT_MOST),
        )
        val width = label.measuredWidth
        val height = label.measuredHeight
        setSize(width, height)

        // Clamp into the visible width so a caret scrolled off to the right pins at the edge
        // instead of vanishing. Only clamp when the editor has a known width.
        val maxX = boundEditor.width - width
        val x = if (maxX > 0) rawX.coerceIn(0, maxX) else rawX
        val y = (boundEditor.rowHeight * line) - boundEditor.offsetY - height
        Log.d(TAG, "[MARKERS-HOST] $peerName editor=${boundEditor.width}x${boundEditor.height} raw=$rawX -> ($x,$y) wasShowing=$isShowing")
        setLocationAbsolutely(x, y)
        if (!isShowing) show()
    }

    private fun contrastingTextColor(background: Int): Int {
        val r = Color.red(background) / 255.0
        val g = Color.green(background) / 255.0
        val b = Color.blue(background) / 255.0
        val luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b
        return if (luminance > 0.6) Color.parseColor("#0A0A0A") else Color.WHITE
    }

    private companion object {
        const val TAG = "PairTrace"
    }
}
