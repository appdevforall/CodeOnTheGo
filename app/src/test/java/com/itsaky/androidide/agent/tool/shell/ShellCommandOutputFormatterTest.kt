package com.itsaky.androidide.agent.tool.shell

import org.junit.Assert.assertTrue
import org.junit.Test

class ShellCommandOutputFormatterTest {

    @Test
    fun `marks output as truncated when exceeding limit`() {
        val longText = buildString {
            repeat(10_000) { append('a') }
        }
        val result = ShellCommandResult(
            exitCode = 0,
            stdout = longText,
            stderr = ""
        )

        val formatted = ShellCommandOutputFormatter.format(result)

        assertTrue(formatted.truncated)
        assertTrue(formatted.text.contains("omitted"))
    }
}
