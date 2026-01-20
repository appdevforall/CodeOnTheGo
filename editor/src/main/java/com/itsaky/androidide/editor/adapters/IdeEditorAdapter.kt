package com.itsaky.androidide.editor.adapters

import android.view.View
import com.itsaky.androidide.actions.MutableTextTarget
import com.itsaky.androidide.editor.ui.IDEEditor

class IdeEditorAdapter(private val editor: IDEEditor) : MutableTextTarget {
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