package com.itsaky.androidide.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// The tool descriptions are the only signal an agent gets about when to reach
// for a tool, and the initialize instructions are the only signal about what
// the server is for at all. Both are asserted here rather than left to review.
class ServerDescriptionTest {
	@Test
	fun `initialize carries instructions describing what the server drives`() =
		withConnectedClient({ cogoMcpServer() }) { client ->
			val instructions = client.serverInstructions

			assertNotNull(instructions, "server sent no instructions on initialize")
			assertTrue(instructions.contains("Code On The Go"), instructions)
			assertTrue(instructions.contains("adb"), instructions)
		}

	@Test
	fun `does not advertise listChanged, since the tool set is static`() =
		withConnectedClient({ cogoMcpServer() }) { client ->
			assertEquals(false, client.serverCapabilities?.tools?.listChanged)
		}

	@Test
	fun `every tool advertises a human readable title`() =
		withConnectedClient({ cogoMcpServer() }) { client ->
			assertEquals(
				mapOf(
					"ping" to "Ping",
					"is_cogo_installed" to "Is Code On The Go installed?",
				),
				client.listTools().tools.associate { it.name to it.title },
			)
		}
}
