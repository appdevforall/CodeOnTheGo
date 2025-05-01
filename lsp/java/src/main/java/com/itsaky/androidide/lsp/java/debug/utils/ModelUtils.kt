package com.itsaky.androidide.lsp.java.debug.utils

import com.itsaky.androidide.lsp.debug.model.Source
import com.sun.jdi.Location
import com.itsaky.androidide.lsp.debug.events.Location as LspLocation

/**
 * Get the [LspLocation] representation of this [Location].
 */
fun Location.asLspLocation(): LspLocation = LspLocation(
    source = Source(
        name = sourceName(),
        path = sourcePath(),
    ),
    line = lineNumber(),
    column = null
)