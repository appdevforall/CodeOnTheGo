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
     * Add breakpoints in the source code.
     *
     * @param request The request definition of the breakpoints to add.
     * @return The response definition of the breakpoints add.
     */
    suspend fun addBreakpoints(request: BreakpointRequest): BreakpointResponse

    /**
     * Remove breakpoints in the source code.
     *
     * @param request The request definition of the breakpoints to remove.
     * @return The response definition of the breakpoints remove.
     */
    suspend fun removeBreakpoints(request: BreakpointRequest): BreakpointResponse
}