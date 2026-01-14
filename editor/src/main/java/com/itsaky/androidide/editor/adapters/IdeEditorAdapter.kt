package com.itsaky.androidide.editor.adapters

import android.content.Context
import android.view.View
import com.itsaky.androidide.actions.TextTarget
import com.itsaky.androidide.editor.ui.IDEEditor

class IdeEditorAdapter(private val editor: IDEEditor) : TextTarget {
    override val context: Context get() = editor.context

    override fun copyText() = editor.copyText()
    override fun cut() = editor.cutText()
    override fun paste() = editor.pasteText()
    override fun selectAll() = editor.selectAll()
    override fun hasSelection() = editor.cursor.isSelected
    override fun isEditable() = editor.isEditable
    override fun getSelectedText(): String? {
        if (!editor.cursor.isSelected) return null
        val cursor = editor.cursor
        return editor.text.subSequence(cursor.left, cursor.right).toString()
    }

    override fun getAnchorView(): View = editor
}