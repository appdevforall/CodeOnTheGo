package com.itsaky.androidide.actions

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.widget.EditText

class EditTextAdapter(private val view: EditText) : MutableTextTarget {
    private val clipboard: ClipboardManager?
        get() = view.context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    override fun copyText() {
        val start = view.selectionStart
        val end = view.selectionEnd
        if (start != end) {
            val content = view.text.subSequence(start, end)
            val clip = ClipData.newPlainText("Copied Text", content)
            clipboard?.setPrimaryClip(clip)
        }
    }

    override fun cut() {
        val start = view.selectionStart
        val end = view.selectionEnd
        if (start < 0 || end < 0) return
        if (start == end) return

        copyText()
        view.text.delete(minOf(start, end), maxOf(start, end))
        view.setSelection(minOf(start, end))
    }

    override fun paste() {
        val clip = clipboard?.primaryClip ?: return
        if (clip.itemCount <= 0) return

        val pasteText = clip.getItemAt(0).coerceToText(view.context) ?: return
        var start = view.selectionStart
        var end = view.selectionEnd

        if (start < 0 || end < 0) {
            view.requestFocus()
            val safePos = view.text?.length ?: 0
            view.setSelection(safePos)
            start = view.selectionStart
            end = view.selectionEnd
        }
        val min = minOf(start, end)
        val max = maxOf(start, end)

        if (min != max) {
            view.text.replace(min, max, pasteText)
            view.setSelection(min + pasteText.length)
        } else {
            view.text.insert(min, pasteText)
            view.setSelection(min + pasteText.length)
        }
    }

    override fun selectAll() {
        view.selectAll()
    }

    override fun hasSelection() = view.hasSelection()
    override fun isEditable() = view.isEnabled

    override fun getSelectedText(): String? {
        val start = view.selectionStart
        val end = view.selectionEnd
        return if (start != end && start >= 0 && end <= view.length()) {
            view.text.subSequence(start, end).toString()
        } else {
            null
        }
    }
    override fun getAnchorView(): View = view
}