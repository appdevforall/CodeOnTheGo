package com.itsaky.androidide.lsp.debug

import com.itsaky.androidide.lsp.debug.model.BreakpointRequest
import com.itsaky.androidide.lsp.debug.model.BreakpointResponse
import com.itsaky.androidide.lsp.debug.model.StepRequestParams
import com.itsaky.androidide.lsp.debug.model.StepResponse
import com.itsaky.androidide.lsp.debug.model.ThreadInfo
import com.itsaky.androidide.lsp.debug.model.ThreadInfoRequestParams
import com.itsaky.androidide.lsp.debug.model.ThreadInfoResponse
import com.itsaky.androidide.lsp.debug.model.ThreadListRequestParams
import com.itsaky.androidide.lsp.debug.model.ThreadListResponse

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

    /**
     * Step through a suspended program.
     */
    suspend fun step(request: StepRequestParams): StepResponse

    /**
     * Get the information about a thread.
     *
     * @param request The request definition of the thread.
     * @return The information about the thread, or `null` if the thread does not exist.
     */
    suspend fun threadInfo(request: ThreadInfoRequestParams): ThreadInfoResponse

    /**
     * Get information about all the threads of the connected VM.
     *
     * @param request The parameters for the request.
     * @return A [ThreadListResponse] containing a list of all known threads of the requested VM.
     */
    suspend fun allThreads(request: ThreadListRequestParams): ThreadListResponse
}