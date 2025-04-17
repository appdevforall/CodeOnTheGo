package com.itsaky.androidide.lsp.debug.events

import com.itsaky.androidide.lsp.debug.RemoteClient

/**
 * An event notifying the debug client that the execution of the program has been stopped.
 *
 * @property reason The reason why the execution of the program has been stopped.
 * @property threadId The id of the thread that has been stopped.
 * @property frameId The id of the frame that has been stopped.
 * @property allThreadsStopped Whether all threads have been stopped.
 * @author Akash Yadav
 */
data class StoppedEvent(
    override val remoteClient: RemoteClient,
    val reason: StopReason,
    val threadId: Int,
    val frameId: Int,
    val allThreadsStopped: Boolean,
): IDebuggerEvent

/**
 * The reason why the execution of the program has been stopped.
 */
enum class StopReason {

    /**
     * A break point was hit.
     */
    Breakpoint,

    /**
     * A step action was completed.
     */
    Step,
}
