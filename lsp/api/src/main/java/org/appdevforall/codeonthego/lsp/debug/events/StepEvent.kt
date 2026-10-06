package org.appdevforall.codeonthego.lsp.debug.events

import org.appdevforall.codeonthego.lsp.debug.RemoteClient
import org.appdevforall.codeonthego.lsp.debug.model.HasThreadInfo
import org.appdevforall.codeonthego.lsp.debug.model.LocatableEvent
import org.appdevforall.codeonthego.lsp.debug.model.Location

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
