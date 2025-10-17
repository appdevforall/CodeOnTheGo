package com.itsaky.androidide.agent.tool

import android.content.Context
import com.itsaky.androidide.agent.model.ShellCommandArgs
import com.itsaky.androidide.agent.model.ToolResult
import com.itsaky.androidide.app.IDEApplication
import com.itsaky.androidide.projects.IProjectManager
import com.itsaky.androidide.utils.Environment
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.io.File

class ExecuteShellCommandHandler(
    private val runner: ShellCommandRunner = TermuxShellCommandRunner()
) : ToolHandler {

    override val name: String = TOOL_NAME
    override val isPotentiallyDangerous: Boolean = true

    override suspend fun invoke(args: Map<String, Any?>): ToolResult {
        val toolArgs = decodeArgs<ShellCommandArgs>(args)
        val command = toolArgs.command.trim()
        if (command.isEmpty()) {
            return ToolResult.failure("The 'command' parameter cannot be empty.")
        }

        val result = runner.run(command)
        val payload = encodePayload(result)
        return if (result.exitCode == 0) {
            ToolResult.success("Command executed successfully.", payload)
        } else {
            ToolResult(
                success = false,
                message = "Command exited with code ${result.exitCode}.",
                data = payload,
                error_details = result.stderr.ifBlank { null }
            )
        }
    }

    private fun encodePayload(result: ShellCommandResult): String {
        val json = buildJsonObject {
            put("stdout", result.stdout)
            put("stderr", result.stderr)
            put("exit_code", result.exitCode)
            result.workingDirectory?.let { put("working_directory", it) }
        }
        return toolJson.encodeToString(JsonObject.serializer(), json)
    }

    companion object {
        const val TOOL_NAME = "execute_shell_command"
    }
}

data class ShellCommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val workingDirectory: String? = null
)

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
        if (!projectDir.exists() || !projectDir.isDirectory) {
            log.warn("Project directory does not exist: {}", projectDir.absolutePath)
            return ShellCommandResult(
                exitCode = -1,
                stdout = "",
                stderr = "Project directory does not exist.",
                workingDirectory = projectDir.absolutePath
            )
        }

        val shell = shellFileProvider()
        if (!shell.exists() || !shell.canExecute()) {
            log.warn("Shell binary not found or not executable: {}", shell.absolutePath)
            return ShellCommandResult(
                exitCode = -1,
                stdout = "",
                stderr = "Shell binary not available at ${shell.absolutePath}",
                workingDirectory = projectDir.absolutePath
            )
        }

        val environment = HashMap(environmentProvider(context))
        Environment.putEnvironment(environment, false)

        return withContext(Dispatchers.IO) {
            runCommand(shell, command, projectDir, environment)
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
            return@coroutineScope ShellCommandResult(
                exitCode = -1,
                stdout = "",
                stderr = t.message ?: t.toString(),
                workingDirectory = workingDirectory.absolutePath
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

            ShellCommandResult(
                exitCode = exitCode,
                stdout = stdout.trimEnd(),
                stderr = stderr.trimEnd(),
                workingDirectory = workingDirectory.absolutePath
            )
        } finally {
            process.destroy()
        }
    }
}
