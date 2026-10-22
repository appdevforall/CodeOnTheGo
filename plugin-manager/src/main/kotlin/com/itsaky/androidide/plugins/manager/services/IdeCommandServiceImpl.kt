package com.itsaky.androidide.plugins.manager.services

import com.itsaky.androidide.plugins.PluginPermission
import com.itsaky.androidide.plugins.extensions.CommandOutput
import com.itsaky.androidide.plugins.extensions.CommandResult
import com.itsaky.androidide.plugins.extensions.CommandSpec
import com.itsaky.androidide.plugins.services.CommandExecution
import com.itsaky.androidide.plugins.services.IdeCommandService
import com.itsaky.androidide.utils.TermuxProcessEnvironment
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

class IdeCommandServiceImpl internal constructor(
	private val pluginId: String,
	private val permissions: Set<PluginPermission>,
	private val projectRootProvider: () -> File?,
	private val appFilesDir: File,
	private val gradleTaskRunner: GradleTaskRunner = GradleTaskRunner(IdeBuildServiceImpl.getInstance()::startTasks),
) : IdeCommandService {
	private val runningCommands = ConcurrentHashMap<String, RunningCommand>()

	override fun executeCommand(
		spec: CommandSpec,
		timeoutMs: Long,
	): CommandExecution {
		requirePermission()
		requireConcurrencyLimit()

		val executionId = "$pluginId-${UUID.randomUUID()}"
		val execution =
			when (spec) {
				// Through the tooling server, never ./gradlew: a second daemon doubles Gradle's memory
				// on the device, and its output would never reach the Build Output pane.
				is CommandSpec.GradleTask -> GradleTaskExecution(executionId, spec, gradleTaskRunner, timeoutMs)

				is CommandSpec.ShellCommand -> CommandExecutionImpl(executionId, shellProcess(spec), timeoutMs)
			}
		runningCommands[executionId] = execution
		execution.start { runningCommands.remove(executionId) }
		return execution
	}

	private fun shellProcess(spec: CommandSpec.ShellCommand): ProcessBuilder {
		val workDir = resolvePluginWorkingDirectory(pluginId, projectRootProvider(), spec.workingDirectory)
		return ProcessBuilder(listOf(spec.executable) + spec.arguments).apply {
			workDir?.let { directory(it) }
			environment().putAll(spec.environment)
			redirectErrorStream(false)
			TermuxProcessEnvironment.applyTo(environment(), appFilesDir)
		}
	}

	override fun isCommandRunning(executionId: String): Boolean = runningCommands[executionId]?.isRunning() == true

	// onComplete removes the entry: a GradleTask stays running until Gradle has stopped.
	override fun cancelCommand(executionId: String): Boolean =
		runningCommands[executionId]?.let {
			it.cancel()
			true
		} ?: false

	override fun getRunningCommandCount(): Int = runningCommands.size

	fun cancelAllCommands() {
		runningCommands.entries.removeAll { (_, execution) ->
			execution.cancel()
			true
		}
	}

	private fun requirePermission() {
		if (PluginPermission.SYSTEM_COMMANDS !in permissions) {
			throw SecurityException(
				"Plugin $pluginId does not have SYSTEM_COMMANDS permission",
			)
		}
	}

	private fun requireConcurrencyLimit() {
		if (runningCommands.size >= MAX_CONCURRENT_COMMANDS) {
			throw IllegalStateException(
				"Plugin $pluginId has reached the maximum of $MAX_CONCURRENT_COMMANDS concurrent commands",
			)
		}
	}

	companion object {
		private const val MAX_CONCURRENT_COMMANDS = 3
	}
}

internal interface RunningCommand : CommandExecution {
	/** Starts the command; [onComplete] runs once it ends or is cancelled. */
	fun start(onComplete: () -> Unit)

	fun isRunning(): Boolean
}

private class CommandExecutionImpl(
	override val executionId: String,
	private val processBuilder: ProcessBuilder,
	private val timeoutMs: Long,
) : RunningCommand {
	private val outputChannel = Channel<CommandOutput>(capacity = Channel.UNLIMITED)
	private val resultDeferred = CompletableDeferred<CommandResult>()
	private val scope = CoroutineScope(Dispatchers.IO + Job())
	private var process: Process? = null
	private val stdoutBuilder = StringBuilder()
	private val stderrBuilder = StringBuilder()

	// Also run by cancel(): a cancel landing before the launched body starts skips its call.
	@Volatile
	private var onComplete: () -> Unit = {}

	override val output: Flow<CommandOutput> = outputChannel.receiveAsFlow()

	override fun start(onComplete: () -> Unit) {
		this.onComplete = onComplete
		scope.launch {
			val startTime = System.currentTimeMillis()

			runCatching {
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
					if (exitCode == 0) {
						CommandResult.Success(exitCode, stdoutBuilder.toString(), stderrBuilder.toString(), duration)
					} else {
						CommandResult.Failure(exitCode, stdoutBuilder.toString(), stderrBuilder.toString(), null, duration)
					}
				}
			}.onSuccess { result ->
				resultDeferred.complete(result)
			}.onFailure { e ->
				process?.destroyForcibly()
				outputChannel.close()
				val stdout = stdoutBuilder.toString()
				val stderr = stderrBuilder.toString()
				val duration = System.currentTimeMillis() - startTime
				val failureResult =
					when (e) {
						is kotlinx.coroutines.TimeoutCancellationException -> {
							CommandResult.Failure(-1, stdout, stderr, "Command timed out after ${timeoutMs}ms: ${e.message}", duration)
						}

						is kotlinx.coroutines.CancellationException -> {
							CommandResult.Cancelled(stdout, stderr)
						}

						else -> {
							CommandResult.Failure(-1, stdout, stderr, "Unexpected error: ${e.message}", duration)
						}
					}
				if (resultDeferred.isActive) {
					resultDeferred.complete(failureResult)
				}
			}

			onComplete()
		}
	}

	private suspend fun readStream(
		process: Process,
		isStdErr: Boolean,
	) {
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
				CommandResult.Cancelled(stdoutBuilder.toString(), stderrBuilder.toString()),
			)
		}
		scope.cancel()
		onComplete()
	}

	override fun isRunning(): Boolean = process?.isAlive == true

	companion object {
		private const val MAX_OUTPUT_BYTES = 10 * 1024 * 1024
	}
}
