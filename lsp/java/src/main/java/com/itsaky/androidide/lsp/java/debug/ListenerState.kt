package com.itsaky.androidide.lsp.java.debug

import com.itsaky.androidide.lsp.debug.IDebugClient
import com.sun.jdi.VirtualMachine
import com.sun.jdi.connect.Connector
import com.sun.tools.jdi.SocketListeningConnector
import com.sun.tools.jdi.isListening
import java.util.concurrent.atomic.AtomicBoolean

internal data class ListenerState(
	val client: IDebugClient,
	val connector: SocketListeningConnector,
	val args: Map<String, Connector.Argument>,
) {
	private val invalidated = AtomicBoolean(false)

	val isListening: Boolean
		get() = connector.isListening(args)

	/**
	 * Whether this listener state has been invalidated.
	 */
	val isInvalidated: Boolean
		get() = invalidated.get()

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

	/**
	 * Invalidate this listener state.
	 */
	fun invalidate() {
		if (isListening) {
			stopListening()
		}

		invalidated.set(true)
	}
}
