package com.itsaky.androidide.lsp.java.debug.transport

import com.sun.jdi.connect.spi.TransportService

internal class SocketTransportServiceCapabilities : TransportService.Capabilities() {
	override fun supportsMultipleConnections(): Boolean = true

	override fun supportsAttachTimeout(): Boolean = true

	override fun supportsAcceptTimeout(): Boolean = true

	override fun supportsHandshakeTimeout(): Boolean = true
}
