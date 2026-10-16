package com.itsaky.androidide.mcp

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp

const val DEFAULT_PORT = 3000

fun main(args: Array<String>) {
	val port = args.firstOrNull()?.toIntOrNull() ?: DEFAULT_PORT

	// Loopback only: this server is unauthenticated.
	embeddedServer(CIO, host = "127.0.0.1", port = port) {
		mcpStreamableHttp { cogoMcpServer() }
	}.start(wait = true)
}
