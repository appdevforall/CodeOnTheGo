package com.itsaky.androidide.lsp.java.debug

import android.net.TrafficStats
import com.itsaky.androidide.lsp.debug.IDebugClient
import com.sun.jdi.VirtualMachine
import com.sun.jdi.connect.Connector
import com.sun.tools.jdi.SocketListeningConnector
import com.sun.tools.jdi.isListening
import java.util.concurrent.atomic.AtomicBoolean

internal data class ListenerState(
	val client: IDebugClient,
	val connector: SocketListeningConnector,
	val args: Map<String, Connector.Argument>
) {
	private val invalidated = AtomicBoolean(false)

	private companion object {
		// Tag used to identify the JDWP listener socket for traffic-stats accounting.
		private const val JDWP_LISTENER_SOCKET_TAG = 0x444A574C // "JDWL"
	}

	/**
	 * Whether we're currently listening for incoming connections.
	 */
	val isListening: Boolean
		get() = connector.isListening(args)

	/**
	 * Whether this listener state has been invalidated.
	 */
	val isInvalidated: Boolean
		get() = invalidated.get()

	/**
	 * The address we're listening at. May be `null` when we're not listening.
	 */
	var listenAddress: String? = null
		private set
		get() = field.takeIf { isListening }

	/**
	 * Start listening for connections from VMs.
	 *
	 * @return The address of the listening socket.
	 */
	fun startListening(): String {
		val previousTag = TrafficStats.getThreadStatsTag()
		TrafficStats.setThreadStatsTag(JDWP_LISTENER_SOCKET_TAG)
		val address = try {
			connector.startListening(args)
		} finally {
			TrafficStats.setThreadStatsTag(previousTag)
		}
		listenAddress = address
		return address
	}

	/**
	 * Invalidate this listener state.
	 */
	fun invalidate() {
		if (isListening) {
			stopListening()
		}

		invalidated.set(true)
	}

	/**
	 * Stop listening for connections from VMs.
	 */
	fun stopListening() {
		try {
			connector.stopListening(args)
		} finally {
			invalidated.set(true)
			listenAddress = null
		}
	}

	/**
	 * Accept a connection from a VM.
	 *
	 * @return The connected VM.
	 */
	fun accept(): VirtualMachine = connector.accept(args)
}