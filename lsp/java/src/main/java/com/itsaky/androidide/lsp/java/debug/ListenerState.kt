package com.itsaky.androidide.lsp.java.debug

import com.itsaky.androidide.lsp.debug.IDebugClient
import com.sun.jdi.VirtualMachine
import com.sun.jdi.connect.Connector
import com.sun.tools.jdi.SocketListeningConnector

internal data class ListenerState(
    val client: IDebugClient,
    val connector: SocketListeningConnector,
    val args: Map<String, Connector.Argument>
) {

    /**
     * Start listening for connections from VMs.
     *
     * @return The address of the listening socket.
     */
    fun startListening(): String = connector.startListening(args)

    /**
     * Stop listening for connections from VMs.
     */
    fun stopListening() = connector.stopListening(args)

    /**
     * Accept a connection from a VM.
     *
     * @return The connected VM.
     */
    fun accept(): VirtualMachine = connector.accept(args)
}