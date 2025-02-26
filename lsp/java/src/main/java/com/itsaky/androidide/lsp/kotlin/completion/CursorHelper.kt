package com.itsaky.androidide.lsp.kotlin.completion

object CursorHelper {

    fun getPrefixBeforeCursor(content: String, cursorIndex: Int): String {
        var start = cursorIndex
        while (start > 0 && Character.isJavaIdentifierPart(content[start - 1])) {
            start--
        }
        return content.substring(start, cursorIndex)
    }
}