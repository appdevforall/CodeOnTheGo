package com.itsaky.androidide.mcp

import kotlin.test.Test
import kotlin.test.assertEquals

// Exercises the real process boundary against commands that exist everywhere,
// so the adapter is covered without depending on adb or an attached device.
class SystemAdbTest {
	@Test
	fun `captures stdout and a zero exit code`() {
		val result = SystemAdb(executable = "/bin/echo").run(listOf("hello"))

		assertEquals(0, result.exitCode)
		assertEquals("hello", result.stdout.trim())
	}

	@Test
	fun `propagates a non-zero exit code and stderr`() {
		val result = SystemAdb(executable = "/bin/sh").run(listOf("-c", "echo boom >&2; exit 3"))

		assertEquals(3, result.exitCode)
		assertEquals("boom", result.stderr.trim())
	}
}
