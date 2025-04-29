package com.itsaky.androidide.lsp.debug.events

import com.itsaky.androidide.lsp.debug.RemoteClient

/**
 * An event or response to a debugger event.
 *
 * @author Akash Yadav
 */
interface EventOrResponse {

    /**
     * The remote client that triggered this event.
     */
    val remoteClient: RemoteClient
}

/**
 * A debugger event.
 */
interface DebugEvent: EventOrResponse

/**
 * A response to a debugger event.
 */
interface DebugEventResponse: EventOrResponse
