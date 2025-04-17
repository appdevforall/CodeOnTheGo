package com.itsaky.androidide.lsp.debug.model

/**
 * Data about a source file.
 *
 * @property name The name of the source file.
 * @property path The path to the source file.
 *
 * @author Akash Yadav
 */
data class Source(
    val name: String,
    val path: String,
)
