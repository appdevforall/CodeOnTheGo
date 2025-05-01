package com.itsaky.androidide.lsp.debug.events

import com.itsaky.androidide.lsp.debug.RemoteClient

/**
 * Describes a step event in a client.
 *
 * @author Akash Yadav
 */
data class StepEvent(
    override val remoteClient: RemoteClient,
    override val threadInfo: ThreadInfo,
    override val location: Location,
): EventOrResponse, LocatableEvent, HasThreadInfo

/**
 * Response to a [StepEvent].
 */
typealias StepEventResponse = Unit
