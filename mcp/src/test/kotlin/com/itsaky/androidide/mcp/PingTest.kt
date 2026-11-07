package com.itsaky.androidide.mcp

import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlin.test.Test
import kotlin.test.assertEquals

class PingTest {
	@Test
	fun `handshake reports the server identity`() =
		withConnectedClient({ cogoMcpServer() }) { client ->
			assertEquals("cogo-mcp", client.serverVersion?.name)
		}

	@Test
	fun `tools list contains exactly the registered tools`() =
		withConnectedClient({ cogoMcpServer() }) { client ->
			assertEquals(
				setOf(
					"ping",
					"is_cogo_installed",
					"cogo_home",
					"list_projects",
					"list_templates",
					"list_project_files",
				),
				client
					.listTools()
					.tools
					.map { it.name }
					.toSet(),
			)
		}

	@Test
	fun `calling ping returns pong`() =
		withConnectedClient({ cogoMcpServer() }) { client ->
			val result = client.callTool(name = "ping", arguments = emptyMap())
			val text =
				result.content
					.filterIsInstance<TextContent>()
					.single()
					.text
			assertEquals("pong", text)
		}
}
