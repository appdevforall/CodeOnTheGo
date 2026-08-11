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
const val COGO_PACKAGE = "com.itsaky.androidide"

fun cogoMcpServer(adb: Adb = SystemAdb()): Server {
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

	server.addTool(
		name = "is_cogo_installed",
		description = "Report whether Code On The Go ($COGO_PACKAGE) is installed on the attached device.",
		inputSchema = ToolSchema(properties = JsonObject(emptyMap())),
	) { _ ->
		isCogoInstalled(adb)
	}

	return server
}

private fun isCogoInstalled(adb: Adb): CallToolResult {
	val result = adb.run(listOf("shell", "pm", "list", "packages", COGO_PACKAGE))

	// A failed adb call means we do not know, which is not the same as "not
	// installed" - report it as an error rather than a negative answer.
	if (result.exitCode != 0) {
		val detail = result.stderr.trim().ifEmpty { result.stdout.trim() }
		return CallToolResult(
			content = listOf(TextContent("adb failed (exit ${result.exitCode}): $detail")),
			isError = true,
		)
	}

	// adb shell emits CRLF, so trim before comparing.
	val installed =
		result.stdout
			.lineSequence()
			.map { it.trim() }
			.filter { it.startsWith("package:") }
			.map { it.removePrefix("package:") }
			.any { it == COGO_PACKAGE }

	val message =
		if (installed) {
			"Code On The Go ($COGO_PACKAGE) is installed."
		} else {
			"Code On The Go ($COGO_PACKAGE) is NOT installed."
		}

	return CallToolResult(content = listOf(TextContent(message)))
}
