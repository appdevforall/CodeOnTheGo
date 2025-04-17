package com.itsaky.androidide.lsp.debug

import com.itsaky.androidide.lsp.debug.model.BreakpointRequest
import com.itsaky.androidide.lsp.debug.model.BreakpointResponse

/**
 * A debug adapter provides support for debugging a given type of file.
 *
 * @author Akash Yadav
 */
interface IDebugAdapter {
    /**
     * Connect the debug adapter to the given client.
     *
     * @param client The client to connect to.
     */
    fun connectDebugClient(client: IDebugClient)

    /**
     * Get the remote clients connected to this debug adapter.
     *
     * @return The set of remote clients.
     */
    suspend fun connectedRemoteClients(): Set<RemoteClient>

    /**
     * Set breakpoints in the source code.
     *
     * @param request The request definition of the breakpoints to set.
     * @return The response definition of the breakpoints set.
     */
    suspend fun setBreakpoints(request: BreakpointRequest): BreakpointResponse
}