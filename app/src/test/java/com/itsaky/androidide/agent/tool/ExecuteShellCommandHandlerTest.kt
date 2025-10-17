package com.itsaky.androidide.agent.tool

import com.itsaky.androidide.agent.model.ToolResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecuteShellCommandHandlerTest {

    @Test
    fun `returns failure when command blank`() = runTest {
        val handler = ExecuteShellCommandHandler(runner = rejectingRunner())

        val result = handler.invoke(mapOf("command" to "   "))

        assertFalse(result.success)
        assertEquals("The 'command' parameter cannot be empty.", result.message)
    }

    @Test
    fun `trims command and returns success payload`() = runTest {
        val runner = CapturingRunner(
            ShellCommandResult(
                exitCode = 0,
                stdout = "hello\nworld",
                stderr = "",
                workingDirectory = "/tmp/project"
            )
        )
        val handler = ExecuteShellCommandHandler(runner = runner)

        val result = handler.invoke(mapOf("command" to "  echo \"hello\"  "))

        assertTrue(result.success)
        assertEquals("echo \"hello\"", runner.lastCommand)
        val payload = decodePayload(result)
        assertEquals("hello\nworld", payload["stdout"]!!.jsonPrimitive.content)
        assertEquals("", payload["stderr"]!!.jsonPrimitive.content)
        assertEquals(0, payload["exit_code"]!!.jsonPrimitive.int)
        assertEquals("/tmp/project", payload["working_directory"]!!.jsonPrimitive.content)
    }

    @Test
    fun `propagates non-zero exit code as failure`() = runTest {
        val handler = ExecuteShellCommandHandler(
            runner = CapturingRunner(
                ShellCommandResult(
                    exitCode = 2,
                    stdout = "",
                    stderr = "command not found",
                    workingDirectory = "/tmp/project"
                )
            )
        )

        val result = handler.invoke(mapOf("command" to "ls /missing"))

        assertFalse(result.success)
        assertEquals("Command exited with code 2.", result.message)
        val payload = decodePayload(result)
        assertEquals(2, payload["exit_code"]!!.jsonPrimitive.int)
    }

    private fun decodePayload(result: ToolResult): JsonObject {
        val data = result.data ?: error("Expected data payload")
        return toolJson.decodeFromString(JsonObject.serializer(), data)
    }

    private fun rejectingRunner(): ShellCommandRunner = object : ShellCommandRunner {
        override suspend fun run(command: String): ShellCommandResult {
            error("Runner should not be invoked for blank command.")
        }
    }

    private class CapturingRunner(
        private val response: ShellCommandResult
    ) : ShellCommandRunner {
        var lastCommand: String? = null
            private set

        override suspend fun run(command: String): ShellCommandResult {
            lastCommand = command
            return response
        }
    }
}
