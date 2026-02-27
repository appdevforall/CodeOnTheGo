package com.itsaky.androidide.lsp.java.debug.transport

import androidx.annotation.Keep
import com.sun.jdi.connect.Connector
import com.sun.jdi.connect.IllegalConnectorArgumentsException
import com.sun.jdi.connect.Transport
import com.sun.tools.jdi.GenericListeningConnector
import com.sun.tools.jdi._addIntegerArgument
import com.sun.tools.jdi._addStringArgument
import com.sun.tools.jdi._argument
import com.sun.tools.jdi._getString
import com.sun.tools.jdi._transport
import java.io.IOException

@Keep
class COTGSocketListeningConnector : GenericListeningConnector {
	companion object {
		const val ARG_PORT: String = "port"
		const val ARG_LOCALADDR: String = "localAddress"
	}

	constructor() : super(COTGSocketTransportService()) {
		this._addIntegerArgument(
			"port",
			this._getString("socket_listening.port.label"),
			this._getString("socket_listening.port"),
			"",
			false,
			0,
			Int.MAX_VALUE,
		)
		this._addStringArgument(
			"localAddress",
			this._getString("socket_listening.localaddr.label"),
			this._getString("socket_listening.localaddr"),
			"",
			false,
		)

		this._transport = Transport { "dt_socket" }
	}

	@Throws(IOException::class, IllegalConnectorArgumentsException::class)
	override fun startListening(args: Map<String, Connector.Argument>): String {
		var port = this._argument("port", args).value()
		var localaddr = this._argument("localAddress", args).value()
		if (port.isEmpty()) {
			port = "0"
		}

		localaddr =
			if (localaddr.isNotEmpty()) {
				"$localaddr:$port"
			} else {
				port
			}

		return super.startListening(localaddr, args)
	}

	override fun name(): String = "com.sun.jdi.SocketListen"

	override fun description(): String = this._getString("socket_listening.description")
}
