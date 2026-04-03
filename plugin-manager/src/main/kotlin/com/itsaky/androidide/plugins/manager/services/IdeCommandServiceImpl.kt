package com.itsaky.androidide.plugins.manager.services

import com.itsaky.androidide.plugins.PluginPermission
import com.itsaky.androidide.plugins.extensions.CommandOutput
import com.itsaky.androidide.plugins.extensions.CommandResult
import com.itsaky.androidide.plugins.extensions.CommandSpec
import com.itsaky.androidide.plugins.services.CommandExecution
import com.itsaky.androidide.plugins.services.IdeCommandService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class IdeCommandServiceImpl(
    private val pluginId: String,
    private val permissions: Set<PluginPermission>,
    private val projectRootProvider: () -> File?,
    private val appFilesDir: File
) : IdeCommandService {

    private val runningCommands = ConcurrentHashMap<String, CommandExecutionImpl>()

    override fun executeCommand(spec: CommandSpec, timeoutMs: Long): CommandExecution {
        requirePermission()
        requireConcurrencyLimit()

        val executionId = "$pluginId-${UUID.randomUUID()}"
        val projectRoot = projectRootProvider()

        val processBuilder = when (spec) {
            is CommandSpec.ShellCommand -> {
                val workDir = spec.workingDirectory?.let { File(it) } ?: projectRoot
                validateWorkingDirectory(workDir)
                ProcessBuilder(listOf(spec.executable) + spec.arguments).apply {
                    workDir?.let { directory(it) }
                    environment().putAll(spec.environment)
                }
            }
            is CommandSpec.GradleTask -> {
                val gradleWrapper = projectRoot?.let { File(it, "gradlew") }
                    ?: throw IllegalStateException("No project root available for Gradle task execution")
                ProcessBuilder(listOf(gradleWrapper.absolutePath, spec.taskPath) + spec.arguments).apply {
                    directory(projectRoot)
                }
            }
        }

        processBuilder.redirectErrorStream(false)
        injectTermuxEnvironment(processBuilder)

        val execution = CommandExecutionImpl(
            executionId = executionId,
            processBuilder = processBuilder,
            timeoutMs = timeoutMs
        )
        runningCommands[executionId] = execution
        execution.start { runningCommands.remove(executionId) }
        return execution
    }

    override fun isCommandRunning(executionId: String): Boolean {
        return runningCommands[executionId]?.isRunning() == true
    }

    override fun cancelCommand(executionId: String): Boolean {
        return runningCommands[executionId]?.let {
            it.cancel()
            true
        } ?: false
    }

    override fun getRunningCommandCount(): Int = runningCommands.size

    fun cancelAllCommands() {
        runningCommands.values.forEach { it.cancel() }
        runningCommands.clear()
    }

    private fun requirePermission() {
        if (PluginPermission.SYSTEM_COMMANDS !in permissions) {
            throw SecurityException(
                "Plugin $pluginId does not have SYSTEM_COMMANDS permission"
            )
        }
    }

    private fun requireConcurrencyLimit() {
        if (runningCommands.size >= MAX_CONCURRENT_COMMANDS) {
            throw IllegalStateException(
                "Plugin $pluginId has reached the maximum of $MAX_CONCURRENT_COMMANDS concurrent commands"
            )
        }
    }

    private fun validateWorkingDirectory(dir: File?) {
        if (dir == null) return
        val projectRoot = projectRootProvider() ?: return
        val canonicalDir = dir.canonicalPath
        val canonicalRoot = projectRoot.canonicalPath
        if (!canonicalDir.startsWith(canonicalRoot)) {
            throw SecurityException(
                "Plugin $pluginId attempted to execute in directory outside project root: $canonicalDir"
            )
        }
    }

    private fun injectTermuxEnvironment(processBuilder: ProcessBuilder) {
        val termuxBase = appFilesDir.absolutePath
        val termuxBin = "$termuxBase/usr/bin"
        val termuxLib = "$termuxBase/usr/lib"
        val env = processBuilder.environment()

        val existingPath = env["PATH"] ?: ""
        if (!existingPath.contains(termuxBin)) {
            env["PATH"] = "$termuxBin:$existingPath"
        }

        val existingLdPath = env["LD_LIBRARY_PATH"] ?: ""
        if (!existingLdPath.contains(termuxLib)) {
            env["LD_LIBRARY_PATH"] = "$termuxLib:$existingLdPath"
        }

        env.putIfAbsent("HOME", "$termuxBase/home")
        env.putIfAbsent("TMPDIR", "$termuxBase/usr/tmp")
        env.putIfAbsent("LANG", "en_US.UTF-8")
        env.putIfAbsent("PREFIX", "$termuxBase/usr")
    }

    companion object {
        private const val MAX_CONCURRENT_COMMANDS = 3
    }
}

private class CommandExecutionImpl(
    override val executionId: String,
    private val processBuilder: ProcessBuilder,
    private val timeoutMs: Long
) : CommandExecution {

    private val outputChannel = Channel<CommandOutput>(capacity = 256)
    private val resultDeferred = CompletableDeferred<CommandResult>()
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var process: Process? = null
    private val stdoutBuilder = StringBuilder()
    private val stderrBuilder = StringBuilder()

    override val output: Flow<CommandOutput> = outputChannel.receiveAsFlow()

    fun start(onComplete: () -> Unit) {
        scope.launch {
            val startTime = System.currentTimeMillis()
            try {
                withTimeout(timeoutMs) {
                    process = processBuilder.start()
                    val proc = process!!

                    val stdoutJob = launch { readStream(proc, isStdErr = false) }
                    val stderrJob = launch { readStream(proc, isStdErr = true) }

                    val exitCode = proc.waitFor()
                    stdoutJob.join()
                    stderrJob.join()

                    outputChannel.send(CommandOutput.ExitCode(exitCode))
                    outputChannel.close()

                    val duration = System.currentTimeMillis() - startTime
                    val result = if (exitCode == 0) {
                        CommandResult.Success(exitCode, stdoutBuilder.toString(), stderrBuilder.toString(), duration)
                    } else {
                        CommandResult.Failure(exitCode, stdoutBuilder.toString(), stderrBuilder.toString(), null, duration)
                    }
                    resultDeferred.complete(result)
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                process?.destroyForcibly()
                outputChannel.close()
                val duration = System.currentTimeMillis() - startTime
                resultDeferred.complete(
                    CommandResult.Failure(-1, stdoutBuilder.toString(), stderrBuilder.toString(), "Command timed out after ${timeoutMs}ms", duration)
                )
            } catch (e: Exception) {
                process?.destroyForcibly()
                outputChannel.close()
                val duration = System.currentTimeMillis() - startTime
                if (resultDeferred.isActive) {
                    resultDeferred.complete(
                        CommandResult.Cancelled(stdoutBuilder.toString(), stderrBuilder.toString())
                    )
                }
            } finally {
                onComplete()
            }
        }
    }

    private suspend fun readStream(process: Process, isStdErr: Boolean) {
        val stream = if (isStdErr) process.errorStream else process.inputStream
        val builder = if (isStdErr) stderrBuilder else stdoutBuilder
        BufferedReader(InputStreamReader(stream)).use { reader ->
            var line = reader.readLine()
            while (line != null) {
                if (builder.length + line.length <= MAX_OUTPUT_BYTES) {
                    builder.appendLine(line)
                }
                val output = if (isStdErr) CommandOutput.StdErr(line) else CommandOutput.StdOut(line)
                outputChannel.send(output)
                line = reader.readLine()
            }
        }
    }

    override suspend fun await(): CommandResult = resultDeferred.await()

    override fun cancel() {
        process?.destroyForcibly()
        outputChannel.close()
        if (resultDeferred.isActive) {
            resultDeferred.complete(
                CommandResult.Cancelled(stdoutBuilder.toString(), stderrBuilder.toString())
            )
        }
        scope.cancel()
    }

    fun isRunning(): Boolean = process?.isAlive == true

    companion object {
        private const val MAX_OUTPUT_BYTES = 10 * 1024 * 1024
    }
}