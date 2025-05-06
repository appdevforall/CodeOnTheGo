package com.itsaky.androidide.lsp.debug.events

import com.itsaky.androidide.lsp.debug.RemoteClient
import com.itsaky.androidide.lsp.debug.model.HasThreadInfo
import com.itsaky.androidide.lsp.debug.model.LocatableEvent
import com.itsaky.androidide.lsp.debug.model.Location

/**
 * Describes a step event in a client.
 *
 * @author Akash Yadav
 */
data class StepEvent(
    override val remoteClient: RemoteClient,
    override val threadId: String,
    override val location: Location,
): EventOrResponse, LocatableEvent, HasThreadInfo

/**
 * Response to a [StepEvent].
 */
typealias StepEventResponse = Unit
