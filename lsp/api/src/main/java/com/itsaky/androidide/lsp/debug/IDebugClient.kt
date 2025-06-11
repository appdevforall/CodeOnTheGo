package com.itsaky.androidide.lsp.debug

/**
 * Represents a debugger client, usually the IDE.
 *
 * @author Akash Yadav
 */
interface IDebugClient : IDebugEventHandler {

    /**
     * Called when the application being debugged has been attached to the client.
     *
     * @param client The client being debugged.
     */
    fun onAttach(client: RemoteClient)

    /**
     * Called when the application being debugged has been disconnected.
     *
     * @param client The client that disconnected.
     */
    fun onDisconnect(client: RemoteClient)
}