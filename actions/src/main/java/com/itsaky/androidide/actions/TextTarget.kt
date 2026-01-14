package com.itsaky.androidide.actions

import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.widget.EditText

interface TextTarget {
    val context: Context
    fun copyText()
    fun cut()
    fun paste()
    fun selectAll()
    fun hasSelection(): Boolean
    fun isEditable(): Boolean
    fun getSelectedText(): String?
    fun getAnchorView(): View?
}

class EditTextAdapter(private val view: EditText) : TextTarget {
    override val context: Context get() = view.context

    private val clipboard: ClipboardManager?
        get() = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    override fun copyText() {
        val start = view.selectionStart
        val end = view.selectionEnd
        if (start != end) {
            val content = view.text.subSequence(start, end)
            val clip = android.content.ClipData.newPlainText("Copied Text", content)
            clipboard?.setPrimaryClip(clip)
        }
    }

    override fun cut() {
        copyText()
        view.text.delete(view.selectionStart, view.selectionEnd)
    }

    override fun paste() {
        val clip = clipboard?.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).text
            view.text.insert(view.selectionStart, text)
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