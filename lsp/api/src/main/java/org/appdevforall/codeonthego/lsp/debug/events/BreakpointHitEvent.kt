package org.appdevforall.codeonthego.lsp.debug.events

import org.appdevforall.codeonthego.lsp.debug.RemoteClient
import org.appdevforall.codeonthego.lsp.debug.model.HasThreadInfo
import org.appdevforall.codeonthego.lsp.debug.model.LocatableEvent
import org.appdevforall.codeonthego.lsp.debug.model.Location

/**
 * Parameters for when a breakpoint is hit in a target application.
 *
 * @author Akash Yadav
 */
data class BreakpointHitEvent(
    override val remoteClient: RemoteClient,
    override val threadId: String,
    override val location: Location,
): EventOrResponse, LocatableEvent, HasThreadInfo

