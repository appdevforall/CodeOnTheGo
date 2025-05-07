package com.itsaky.androidide.lsp.java.debug.utils

internal fun String.isJavaIdentifier(): Boolean {
    if (isEmpty()) {
        return false
    }

    var cp = codePointAt(0)
    if (!Character.isJavaIdentifierStart(cp)) {
        return false
    }

    var i = Character.charCount(cp)
    while (i < length) {
        cp = codePointAt(i)
        if (!Character.isJavaIdentifierPart(cp)) {
            return false
        }
        i += Character.charCount(cp)
    }

    return true
}