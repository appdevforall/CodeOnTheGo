package com.itsaky.androidide.agent.tool

import android.content.Context
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
import com.itsaky.androidide.app.IDEApplication
import com.itsaky.androidide.projects.IProjectManager
import com.itsaky.androidide.utils.Environment
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID

class ExecuteShellCommandHandler(
    private val runner: ShellCommandRunner = TermuxShellCommandRunner(),
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

interface ShellCommandRunner {
    suspend fun run(command: String): ShellCommandResult
}

class TermuxShellCommandRunner(
    private val context: Context = IDEApplication.instance,
    private val projectDirProvider: () -> File = { IProjectManager.getInstance().projectDir },
    private val shellFileProvider: () -> File = { Environment.BASH_SHELL },
    private val environmentProvider: (Context) -> Map<String, String> = {
        TermuxShellEnvironment().getEnvironment(it, false)
    }
) : ShellCommandRunner {

    private val log = LoggerFactory.getLogger(TermuxShellCommandRunner::class.java)

    override suspend fun run(command: String): ShellCommandResult {
        val projectDir = projectDirProvider()
        val startTime = System.currentTimeMillis()

        fun ShellCommandResult.withDuration(): ShellCommandResult {
            val duration = System.currentTimeMillis() - startTime
            return copy(durationMillis = duration)
        }

        if (!projectDir.exists() || !projectDir.isDirectory) {
            log.warn("Project directory does not exist: {}", projectDir.absolutePath)
            return ShellCommandResult(
                exitCode = -1,
                stdout = "",
                stderr = "Project directory does not exist.",
                workingDirectory = projectDir.absolutePath
            ).withDuration()
        }

        val shell = shellFileProvider()
        if (!shell.exists() || !shell.canExecute()) {
            log.warn("Shell binary not found or not executable: {}", shell.absolutePath)
            return ShellCommandResult(
                exitCode = -1,
                stdout = "",
                stderr = "Shell binary not available at ${shell.absolutePath}",
                workingDirectory = projectDir.absolutePath
            ).withDuration()
        }

        val environment = HashMap(environmentProvider(context))
        Environment.putEnvironment(environment, false)

        return withContext(Dispatchers.IO) {
            runCommand(shell, command, projectDir, environment).withDuration()
        }
    }

    private suspend fun runCommand(
        shell: File,
        command: String,
        workingDirectory: File,
        environment: Map<String, String>
    ): ShellCommandResult = coroutineScope {
        val processBuilder = ProcessBuilder(shell.absolutePath, "-lc", command).apply {
            directory(workingDirectory)
            redirectErrorStream(false)
            environment().putAll(environment)
        }

        val process = try {
            processBuilder.start()
        } catch (t: Throwable) {
            log.error("Failed to start shell process", t)
            val message = sandboxFailureMessageFrom(t)
            return@coroutineScope ShellCommandResult(
                exitCode = -1,
                stdout = "",
                stderr = if (message == null) t.message ?: t.toString() else "",
                workingDirectory = workingDirectory.absolutePath,
                sandboxFailureMessage = message
            )
        }

        try {
            val stdoutDeferred = async(Dispatchers.IO) {
                process.inputStream.bufferedReader().use { it.readText() }
            }
            val stderrDeferred = async(Dispatchers.IO) {
                process.errorStream.bufferedReader().use { it.readText() }
            }

            val exitCode = process.waitFor()
            val stdout = stdoutDeferred.await()
            val stderr = stderrDeferred.await()
            val sandboxFailure = detectSandboxFailure(exitCode, stderr)

            ShellCommandResult(
                exitCode = exitCode,
                stdout = stdout.trimEnd(),
                stderr = if (sandboxFailure != null) "" else stderr.trimEnd(),
                workingDirectory = workingDirectory.absolutePath,
                sandboxFailureMessage = sandboxFailure
            )
        } finally {
            process.destroy()
        }
    }

    private fun sandboxFailureMessageFrom(t: Throwable): String? {
        return when (t) {
            is SecurityException -> t.message ?: "Operation not permitted."
            is IOException -> {
                val message = t.message ?: return null
                if (message.contains("permission denied", ignoreCase = true) ||
                    message.contains("not permitted", ignoreCase = true)
                ) {
                    message
                } else {
                    null
                }
            }

            else -> null
        }
    }

    private fun detectSandboxFailure(exitCode: Int, stderr: String): String? {
        if (stderr.isBlank()) return null
        val normalized = stderr.lowercase(Locale.US)
        val permissionDenied = normalized.contains("permission denied") ||
                normalized.contains("operation not permitted")
        if (!permissionDenied) {
            return null
        }
        if (exitCode == 126 || exitCode == 13 || exitCode == 1) {
            return stderr.trim()
        }
        return null
    }
}
