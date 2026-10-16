package com.itsaky.androidide.mcp

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CogoInstalledTest {
	private fun succeedingAdb(stdout: String) = Adb { AdbResult(exitCode = 0, stdout = stdout, stderr = "") }

	private fun probe(adb: Adb): CallToolResult =
		withConnectedClient({ cogoMcpServer(adb) }) { client ->
			client.callTool(name = "is_cogo_installed", arguments = emptyMap())
		}

	private fun textOf(result: CallToolResult): String =
		result.content
			.filterIsInstance<TextContent>()
			.single()
			.text

	@Test
	fun `reports installed when the package is present`() {
		val result = probe(succeedingAdb("package:com.itsaky.androidide\n"))

		assertEquals(false, result.isError ?: false)
		assertEquals("Code On The Go (com.itsaky.androidide) is installed.", textOf(result))
	}

	@Test
	fun `reports not installed when the package is absent`() {
		val result = probe(succeedingAdb("package:com.android.settings\n"))

		assertEquals(false, result.isError ?: false)
		assertEquals("Code On The Go (com.itsaky.androidide) is NOT installed.", textOf(result))
	}

	// `pm list packages <filter>` matches substrings, so a same-prefix package
	// comes back from the very query used to look for the real one.
	@Test
	fun `does not mistake a same-prefix package for the real one`() {
		val result = probe(succeedingAdb("package:com.itsaky.androidide.debug\r\n"))

		assertEquals(false, result.isError ?: false)
		assertEquals("Code On The Go (com.itsaky.androidide) is NOT installed.", textOf(result))
	}

	// adb shell emits CRLF; an untrimmed compare against "com.itsaky.androidide\r"
	// would silently never match.
	@Test
	fun `tolerates the CRLF line endings adb shell emits`() {
		val result = probe(succeedingAdb("package:com.itsaky.androidide\r\n"))

		assertEquals(false, result.isError ?: false)
		assertEquals("Code On The Go (com.itsaky.androidide) is installed.", textOf(result))
	}

	@Test
	fun `reports an error when adb fails instead of reporting not installed`() {
		val failing = Adb { AdbResult(exitCode = 1, stdout = "", stderr = "adb: device 'x' not found\n") }

		val result = probe(failing)

		assertEquals(true, result.isError)
		assertTrue(textOf(result).contains("device 'x' not found"), textOf(result))
	}
}
