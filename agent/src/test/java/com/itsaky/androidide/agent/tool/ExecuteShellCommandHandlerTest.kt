package com.itsaky.androidide.agent.tool

import com.itsaky.androidide.agent.events.ExecCommandBegin
import com.itsaky.androidide.agent.events.ExecCommandEnd
import com.itsaky.androidide.agent.events.ShellCommandEventEmitter
import com.itsaky.androidide.agent.model.ToolResult
import com.itsaky.androidide.agent.tool.shell.ParsedCommand
import com.itsaky.androidide.agent.tool.shell.ShellCommandPayload
import com.itsaky.androidide.agent.tool.shell.ShellCommandResult
import kotlinx.coroutines.test.runTest
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
    fun `returns formatted output and payload for successful command`() = runTest {
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
        assertEquals("hello\nworld", result.message)
        val payload = decodePayload(result)
        assertEquals("hello\nworld", payload.stdout)
        assertEquals("", payload.stderr)
        assertEquals(0, payload.exitCode)
        assertEquals("/tmp/project", payload.workingDirectory)
        assertTrue(payload.parsedCommand is ParsedCommand.Unknown)
        assertEquals("hello\nworld", payload.formattedOutput)
    }

    @Test
    fun `propagates stderr and exit code on failure`() = runTest {
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
        assertEquals("command not found", result.message)
        val payload = decodePayload(result)
        assertEquals(2, payload.exitCode)
        assertEquals("command not found", payload.formattedOutput)
    }

    @Test
    fun `marks sandbox failure with friendly message`() = runTest {
        val handler = ExecuteShellCommandHandler(
            runner = CapturingRunner(
                ShellCommandResult(
                    exitCode = -1,
                    stdout = "",
                    stderr = "",
                    sandboxFailureMessage = "Permission denied"
                )
            )
        )

        val result = handler.invoke(mapOf("command" to "cat secrets.txt"))

        assertFalse(result.success)
        assertEquals("failed in sandbox: Permission denied", result.message)
        val payload = decodePayload(result)
        assertEquals("Permission denied", payload.sandboxFailureMessage)
        assertEquals("failed in sandbox: Permission denied", payload.formattedOutput)
    }

    @Test
    fun `emits begin and end events`() = runTest {
        val runner = CapturingRunner(
            ShellCommandResult(
                exitCode = 0,
                stdout = "",
                stderr = ""
            )
        )
        val events = mutableListOf<Pair<ExecCommandBegin?, ExecCommandEnd?>>()
        val emitter = object : ShellCommandEventEmitter {
            private var lastBegin: ExecCommandBegin? = null
            override suspend fun onCommandBegin(event: ExecCommandBegin) {
                lastBegin = event
            }

            override suspend fun onCommandEnd(event: ExecCommandEnd) {
                events += lastBegin to event
            }
        }
        val handler = ExecuteShellCommandHandler(runner = runner, eventEmitter = emitter)

        handler.invoke(mapOf("command" to "ls"))

        assertEquals(1, events.size)
        val (begin, end) = events.first()
        require(begin != null)
        assertEquals(begin.callId, end?.callId)
        assertEquals("ls", begin.command)
        assertTrue(end!!.success)
        assertEquals(0, end.exitCode)
    }

    private fun decodePayload(result: ToolResult): ShellCommandPayload {
        val data = result.data ?: error("Expected data payload")
        return toolJson.decodeFromString(ShellCommandPayload.serializer(), data)
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
