package com.itsaky.androidide.mcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject

const val SERVER_NAME = "cogo-mcp"
const val SERVER_VERSION = "0.1.0"

fun cogoMcpServer(): Server {
	val server =
		Server(
			serverInfo = Implementation(name = SERVER_NAME, version = SERVER_VERSION),
			options =
				ServerOptions(
					capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = true)),
				),
		)

	// Handler is suspend ClientConnection.(CallToolRequest) -> CallToolResult: the
	// ClientConnection is the receiver, not a parameter. ping uses neither.
	server.addTool(
		name = "ping",
		description = "Health check. Returns pong.",
		inputSchema = ToolSchema(properties = JsonObject(emptyMap())),
	) { _ ->
		CallToolResult(content = listOf(TextContent("pong")))
	}

	return server
}
