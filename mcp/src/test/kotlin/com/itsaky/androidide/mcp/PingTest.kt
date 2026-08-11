package com.itsaky.androidide.mcp

import io.ktor.client.HttpClient
import io.ktor.client.plugins.sse.SSE
import io.ktor.server.engine.embeddedServer
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.mcpStreamableHttpTransport
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.server.cio.CIO as ServerCIO

class PingTest {
	// Port 0 so the suite never collides with a real server on 3000.
	private fun <T> withConnectedClient(block: suspend (Client) -> T): T =
		runBlocking {
			val engine =
				embeddedServer(ServerCIO, host = "127.0.0.1", port = 0) {
					mcpStreamableHttp { cogoMcpServer() }
				}.start(wait = false)
			try {
				val port =
					engine.engine
						.resolvedConnectors()
						.first()
						.port
				val http = HttpClient(ClientCIO) { install(SSE) }
				try {
					val client = Client(Implementation(name = "cogo-mcp-test", version = "0.1.0"))
					client.connect(http.mcpStreamableHttpTransport("http://127.0.0.1:$port/mcp"))
					block(client)
				} finally {
					http.close()
				}
			} finally {
				engine.stop(gracePeriodMillis = 0, timeoutMillis = 2000)
			}
		}

	@Test
	fun `handshake reports the server identity`() =
		withConnectedClient { client ->
			assertEquals("cogo-mcp", client.serverVersion?.name)
		}

	@Test
	fun `tools list contains exactly ping`() =
		withConnectedClient { client ->
			val tools = client.listTools().tools
			assertEquals(listOf("ping"), tools.map { it.name })
		}

	@Test
	fun `calling ping returns pong`() =
		withConnectedClient { client ->
			val result = client.callTool(name = "ping", arguments = emptyMap())
			val text =
				result.content
					.filterIsInstance<TextContent>()
					.single()
					.text
			assertEquals("pong", text)
		}
}
