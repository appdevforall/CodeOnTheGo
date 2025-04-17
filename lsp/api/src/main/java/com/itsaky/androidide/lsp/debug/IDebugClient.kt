package com.itsaky.androidide.lsp.debug

import com.itsaky.androidide.lsp.debug.events.StoppedEvent

/**
 * Represents a debugger client, usually the IDE.
 *
 * @author Akash Yadav
 */
interface IDebugClient {

    /**
     * Called when the application being debugged has been attached to the client.
     *
     * @param client The client being debugged.
     */
    fun onAttach(client: RemoteClient)

    /**
     * Called when the application being debugged has stopped execution.
     *
     * @param event The event describing the stopped execution.
     */
    fun onStop(event: StoppedEvent)

    /**
     * Called when the application being debugged has terminated.
     *
     * @param client The client that was terminated.
     */
    fun onTerminate(client: RemoteClient)

    /**
     * Called when the application being debugged has died.
     *
     * @param client The client that died.
     */
    fun onDeath(client: RemoteClient)
}