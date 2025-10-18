package com.itsaky.androidide.agent.tool

import com.itsaky.androidide.agent.api.AgentDependencies
import com.itsaky.androidide.agent.events.ExecCommandBegin
import com.itsaky.androidide.agent.events.ExecCommandEnd
import com.itsaky.androidide.agent.events.ShellCommandEventEmitter
import com.itsaky.androidide.agent.model.ExplorationKind
import com.itsaky.androidide.agent.model.ExplorationMetadata
import com.itsaky.androidide.agent.model.ShellCommandArgs
import com.itsaky.androidide.agent.model.ToolResult
import com.itsaky.androidide.agent.tool.shell.ParsedCommand
import com.itsaky.androidide.agent.tool.shell.ShellCommandOutputFormatter
import com.itsaky.androidide.agent.tool.shell.ShellCommandParser
import com.itsaky.androidide.agent.tool.shell.ShellCommandPayload
import com.itsaky.androidide.agent.tool.shell.ShellCommandResult
import kotlinx.serialization.encodeToString
import java.util.UUID

class ExecuteShellCommandHandler(
    private val runner: ShellCommandRunner = ApiShellCommandRunner(),
    private val eventEmitter: ShellCommandEventEmitter = ShellCommandEventEmitter.none()
) : ToolHandler {

    override val name: String = TOOL_NAME
    override val isPotentiallyDangerous: Boolean = true

    override suspend fun invoke(args: Map<String, Any?>): ToolResult {
        val toolArgs = decodeArgs<ShellCommandArgs>(args)
        val command = toolArgs.command.trim()
        if (command.isEmpty()) {
            return ToolResult.failure("The 'command' parameter cannot be empty.")
        }

        val argv = ShellCommandParser.tokenize(command)
        val parsedCommand = ShellCommandParser.parse(argv, command)
        val callId = UUID.randomUUID().toString()
        eventEmitter.onCommandBegin(
            ExecCommandBegin(
                callId = callId,
                command = command,
                argv = argv,
                parsedCommand = parsedCommand
            )
        )

        val result = try {
            runner.run(command)
        } catch (t: Throwable) {
            val failureResult = ShellCommandResult(
                exitCode = -1,
                stdout = "",
                stderr = t.message ?: t.toString()
            )
            val formattedFailure = ShellCommandOutputFormatter.format(failureResult)
            eventEmitter.onCommandEnd(
                ExecCommandEnd(
                    callId = callId,
                    command = command,
                    argv = argv,
                    parsedCommand = parsedCommand,
                    exitCode = failureResult.exitCode,
                    stdout = failureResult.stdout,
                    stderr = failureResult.stderr,
                    formattedOutput = formattedFailure.text,
                    sandboxFailureMessage = failureResult.sandboxFailureMessage,
                    durationMillis = failureResult.durationMillis,
                    truncated = formattedFailure.truncated,
                    success = false
                )
            )
            throw t
        }

        val formatted = ShellCommandOutputFormatter.format(result)
        val payload = ShellCommandPayload(
            command = command,
            argv = argv,
            parsedCommand = parsedCommand,
            stdout = result.stdout,
            stderr = result.stderr,
            exitCode = result.exitCode,
            workingDirectory = result.workingDirectory,
            formattedOutput = formatted.text,
            truncated = formatted.truncated,
            sandboxFailureMessage = result.sandboxFailureMessage,
            durationMillis = result.durationMillis
        )

        val exploration = buildExplorationMetadata(parsedCommand, result)
        val payloadJson = encodePayload(payload)
        eventEmitter.onCommandEnd(
            ExecCommandEnd(
                callId = callId,
                command = command,
                argv = argv,
                parsedCommand = parsedCommand,
                exitCode = result.exitCode,
                stdout = result.stdout,
                stderr = result.stderr,
                formattedOutput = formatted.text,
                sandboxFailureMessage = result.sandboxFailureMessage,
                durationMillis = result.durationMillis,
                truncated = formatted.truncated,
                success = result.isSuccess
            )
        )
        return if (result.isSuccess) {
            ToolResult.success(
                message = formatted.text,
                data = payloadJson,
                exploration = exploration
            )
        } else {
            ToolResult.failure(
                message = formatted.text,
                error_details = result.stderr.ifBlank {
                    result.sandboxFailureMessage ?: formatted.text
                },
                data = payloadJson
            )
        }
    }

    private fun encodePayload(payload: ShellCommandPayload): String {
        return toolJson.encodeToString(payload)
    }

    private fun buildExplorationMetadata(
        parsedCommand: ParsedCommand,
        result: ShellCommandResult
    ): ExplorationMetadata? {
        if (!result.isSuccess || !parsedCommand.isExploration) {
            return null
        }
        return when (parsedCommand) {
            is ParsedCommand.Read -> ExplorationMetadata(
                kind = ExplorationKind.READ,
                items = parsedCommand.files
            )

            is ParsedCommand.ListFiles -> ExplorationMetadata(
                kind = ExplorationKind.LIST,
                path = parsedCommand.path
            )

            is ParsedCommand.Search -> ExplorationMetadata(
                kind = ExplorationKind.SEARCH,
                query = parsedCommand.query,
                path = parsedCommand.path
            )

            is ParsedCommand.Unknown -> null
        }
    }

    companion object {
        const val TOOL_NAME = "shell"
    }
}

fun interface ShellCommandRunner {
    suspend fun run(command: String): ShellCommandResult
}

private class ApiShellCommandRunner : ShellCommandRunner {
    override suspend fun run(command: String): ShellCommandResult {
        return AgentDependencies.requireToolingApi().executeShellCommand(command)
    }
}
