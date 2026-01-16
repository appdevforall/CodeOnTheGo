package com.itsaky.androidide.actions

import android.view.View

interface TextTarget {
    fun copyText()
    fun selectAll()
    fun hasSelection(): Boolean
    fun getSelectedText(): String?
    fun getAnchorView(): View?
}

interface MutableTextTarget : TextTarget {
    fun cut()
    fun paste()
    fun isEditable(): Boolean
}