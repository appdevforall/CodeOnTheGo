package com.itsaky.androidide.lsp.debug

import com.itsaky.androidide.lsp.debug.model.BreakpointRequest
import com.itsaky.androidide.lsp.debug.model.BreakpointResponse
import com.itsaky.androidide.lsp.debug.model.StepRequestParams
import com.itsaky.androidide.lsp.debug.model.StepResponse
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
     * Suspend the execution of the given client. Has no effect if the VM is already suspended.
     *
     * @param client The client to suspend.
     * @return `true` if the client was suspended, `false` otherwise.
     */
    suspend fun suspendClient(client: RemoteClient): Boolean

    /**
     * Resume the execution of the given client. Has no effect if the VM is not suspended.
     *
     * @param client The client to resume.
     * @return `true` if the client was resumed, `false` otherwise.
     */
    suspend fun resumeClient(client: RemoteClient): Boolean

    /**
     * Kill the client process. This may be called when the user wants to stop or restart the
     * debug session.
     *
     * @param client The client to kill.
     * @return `true` if the client was killed, `false` otherwise.
     */
    suspend fun killClient(client: RemoteClient): Boolean

    /**
     * Set breakpoints in source code.
     *
     * @param request The request definition of the breakpoints to set.
     * @return The response definition of the breakpoints set.
     */
    suspend fun setBreakpoints(request: BreakpointRequest): BreakpointResponse

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

    /**
     * Set the value of a variable.
     *
     * @param variableReference The reference ID of the variable.
     * @param newValue The new value to assign.
     * @return true if successful, false otherwise.
     */
    suspend fun setVariable(variableReference: Int, newValue: String): Boolean

}