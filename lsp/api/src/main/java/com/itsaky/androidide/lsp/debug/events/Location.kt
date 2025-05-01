package com.itsaky.androidide.lsp.debug.events

import com.itsaky.androidide.lsp.debug.model.Source

/**
 * An event that has a location.
 */
interface LocatableEvent {

    /**
     * The location of the event.
     */
    val location: Location
}

/**
 * Describes a location in a source file.
 *
 * @property source The source file.
 * @property line The line number.
 * @property column The column number.
 */
data class Location(
    val source: Source,
    val line: Int,
    val column: Int? = null
)