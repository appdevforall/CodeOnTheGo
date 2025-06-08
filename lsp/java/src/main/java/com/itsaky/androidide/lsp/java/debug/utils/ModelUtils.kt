package com.itsaky.androidide.lsp.java.debug.utils

import com.itsaky.androidide.lsp.debug.model.Source
import com.sun.jdi.Location
import com.itsaky.androidide.lsp.debug.model.Location as LspLocation

/**
 * Get the [LspLocation] representation of this [Location].
 */
fun Location.asLspLocation(): LspLocation = LspLocation(
    source = Source(
        name = sourceName(),
        path = sourcePath(),
    ),
    // -1 because we get 1-indexed line numbers from JDI
    // but IDE expects 0-indexed line numbers
    line = lineNumber() - 1,
    column = null
)