package com.itsaky.androidide.lsp.debug.events

import com.itsaky.androidide.lsp.debug.RemoteClient

/**
 * An event that is triggered by the debugger.
 *
 * @author Akash Yadav
 */
interface IDebuggerEvent {

    /**
     * The remote client that triggered this event.
     */
    val remoteClient: RemoteClient
}