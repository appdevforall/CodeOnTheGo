package com.itsaky.androidide.activities.editor

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
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
class EditorDecorationManager(
    private val editorForFile: (File) -> CodeEditor?,
) {

    private val markers: HashMap<String, HashMap<String, RemotePeerMarkerWindow>> = HashMap()

    fun addMarker(
        file: File,
        line: Int,
        column: Int,
        peerId: String,
        peerName: String,
        peerColor: Int,
    ): Boolean {
        val editor = editorForFile(file) ?: return false
        val byPeer = markers.getOrPut(file.absolutePath) { HashMap() }
        val existing = byPeer[peerId]
        val window = if (existing != null && existing.boundEditor === editor) {
            existing
        } else {
            existing?.dismiss()
            RemotePeerMarkerWindow(editor).also { byPeer[peerId] = it }
        }
        window.update(peerName, peerColor, line, column)
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
}

class RemotePeerMarkerWindow(
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
        label.text = peerName
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

        val x = boundEditor.getOffset(line, column).toInt()
        val y = (boundEditor.rowHeight * line) - boundEditor.offsetY - height
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
}
