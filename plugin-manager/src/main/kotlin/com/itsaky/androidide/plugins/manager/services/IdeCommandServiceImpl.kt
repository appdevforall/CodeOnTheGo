package com.itsaky.androidide.plugins.manager.services

import com.itsaky.androidide.plugins.PluginPermission
import com.itsaky.androidide.plugins.extensions.CommandOutput
import com.itsaky.androidide.plugins.extensions.CommandResult
import com.itsaky.androidide.plugins.extensions.CommandSpec
import com.itsaky.androidide.plugins.services.CommandExecution
import com.itsaky.androidide.plugins.services.IdeCommandService
import com.itsaky.androidide.utils.TermuxProcessEnvironment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.file.InvalidPathException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class IdeCommandServiceImpl internal constructor(
	private val pluginId: String,
	private val permissions: Set<PluginPermission>,
	private val projectRootProvider: () -> File?,
	private val appFilesDir: File,
	private val gradleTaskRunner: GradleTaskRunner = GradleTaskRunner(IdeBuildServiceImpl.getInstance()::startTasks),
	private val processGroups: ProcessGroups = ProcessGroups.android(File(appFilesDir, "usr/bin/setsid")),
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
				is CommandSpec.GradleTask -> {
					GradleTaskExecution(executionId, spec, gradleTaskRunner, timeoutMs)
				}

				is CommandSpec.ShellCommand -> {
					val workDir =
						try {
							resolvePluginWorkingDirectory(pluginId, projectRootProvider(), spec.workingDirectory)
						} catch (e: InvalidPathException) {
							return RejectedCommandExecution(executionId, "Invalid working directory: ${e.message}")
						} catch (e: IOException) {
							return RejectedCommandExecution(executionId, "Invalid working directory: ${e.message}")
						}
					val command = processGroups.isolate(listOf(spec.executable) + spec.arguments, workDir)
					val processBuilder =
						try {
							shellProcess(command.command, spec.environment, workDir)
						} catch (e: IllegalArgumentException) {
							return RejectedCommandExecution(executionId, "Invalid environment: ${e.message}")
						}
					CommandExecutionImpl(executionId, processBuilder::start, command.grouped, processGroups, timeoutMs)
				}
			}
		runningCommands[executionId] = execution
		execution.start { runningCommands.remove(executionId) }
		return execution
	}

	private fun shellProcess(
		command: List<String>,
		environment: Map<String, String>,
		workDir: File?,
	): ProcessBuilder =
		ProcessBuilder(command).apply {
			workDir?.let { directory(it) }
			environment().putAll(environment)
			redirectErrorStream(false)
			TermuxProcessEnvironment.applyTo(environment(), appFilesDir)
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

/**
 * A shell command. However it ends - on its own, by timing out, by [cancel], or with an error -
 * nothing it started is left running and no thread is left reading its output, with two
 * exceptions. A command that exits on its own with its output closed may leave a process behind on
 * purpose, as `nohup server >log 2>&1 &` does. And a process that left the group with its own
 * `setsid` is not stopped; on a desktop JVM, its reader is not freed either.
 *
 * @param startProcess starts the process, which is the leader of its own process group if [grouped].
 * @param outputGraceMs how long output may stay open once the command has exited.
 */
internal class CommandExecutionImpl(
	override val executionId: String,
	private val startProcess: () -> Process,
	private val grouped: Boolean,
	private val processGroups: ProcessGroups,
	private val timeoutMs: Long,
	private val outputGraceMs: Long = OUTPUT_GRACE_MS,
) : RunningCommand {
	private val outputChannel = Channel<CommandOutput>(capacity = Channel.UNLIMITED)
	private val resultDeferred = CompletableDeferred<CommandResult>()
	private val scope = CoroutineScope(Dispatchers.IO + Job())

	@Volatile
	private var process: Process? = null

	// Appended to by the reader threads, read by whichever thread ends the command.
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
			var started: Process? = null
			var readers = emptyList<Job>()
			var exitCode: Int? = null
			var outputClosed = false
			var cancelled = false
			var error: Exception? = null
			try {
				withTimeoutOrNull(timeoutMs) {
					// Recorded before the next suspension point, so that a cancel() landing while
					// startProcess() ran, when there was no process yet to stop, has the finally below
					// stop it (ADFA-7109).
					val proc = startProcess()
					started = proc
					process = proc
					readers = listOf(readLines(proc.inputStream, isStdErr = false), readLines(proc.errorStream, isStdErr = true))
					// Interruptible, so that the timeout and cancel() end the wait at once.
					runInterruptible { proc.waitFor() }
				}
				// Read from the process, not the block's result: one that exits just as the time runs
				// out ended on its own.
				exitCode = started?.takeUnless { it.isAlive }?.exitValue()
				if (exitCode != null) {
					// Not under the timeout: the command has ended. A background child can hold the
					// output open long after that, and is then stopped below. Unless no reader was in
					// read() yet when the command exited: Process then drains the pipe and closes its
					// end (ProcessPipeInputStream.processExited), so the output looks closed.
					outputClosed = withTimeoutOrNull(outputGraceMs) { readers.joinAll() } != null
				}
			} catch (e: CancellationException) {
				cancelled = true
			} catch (e: Exception) {
				error = e
			} finally {
				withContext(NonCancellable) {
					started?.let { proc ->
						if (exitCode == null || !outputClosed) processGroups.stop(proc, grouped)
						// Ends the reads of any process that escaped the group or outlived the kill.
						// A desktop JVM, where the unit tests run, does not wake a read on close.
						closeQuietly(proc.inputStream, proc.errorStream, proc.outputStream)
					}
					withTimeoutOrNull(READERS_STOP_MS) { readers.joinAll() }
				}
			}

			val stdout = snapshot(stdoutBuilder)
			val stderr = snapshot(stderrBuilder)
			val duration = System.currentTimeMillis() - startTime
			val result =
				when {
					error != null -> CommandResult.Failure(-1, stdout, stderr, "Unexpected error: ${error.message}", duration)
					exitCode == 0 -> CommandResult.Success(0, stdout, stderr, duration)
					exitCode != null -> CommandResult.Failure(exitCode, stdout, stderr, null, duration)
					cancelled -> CommandResult.Cancelled(stdout, stderr)
					else -> CommandResult.Failure(-1, stdout, stderr, "Command timed out after ${timeoutMs}ms", duration)
				}
			exitCode?.let { outputChannel.trySend(CommandOutput.ExitCode(it)) }
			outputChannel.close()
			// First, so that a caller resuming from await() finds the slot free.
			onComplete()
			// cancel() has already completed it with Cancelled.
			resultDeferred.complete(result)
		}
	}

	private fun readLines(
		stream: InputStream,
		isStdErr: Boolean,
	): Job =
		scope.launch {
			val builder = if (isStdErr) stderrBuilder else stdoutBuilder
			try {
				BufferedReader(InputStreamReader(stream)).use { reader ->
					while (true) {
						val line = reader.readLine() ?: break
						synchronized(builder) {
							if (builder.length + line.length <= MAX_OUTPUT_BYTES) builder.appendLine(line)
						}
						outputChannel.trySend(if (isStdErr) CommandOutput.StdErr(line) else CommandOutput.StdOut(line))
					}
				}
			} catch (e: IOException) {
				// Closed under the read when the command was stopped.
			}
		}

	private fun snapshot(builder: StringBuilder): String = synchronized(builder) { builder.toString() }

	override suspend fun await(): CommandResult = resultDeferred.await()

	override fun cancel() {
		outputChannel.close()
		// First, so that a caller resuming from await() finds the slot free.
		onComplete()
		if (resultDeferred.isActive) {
			resultDeferred.complete(CommandResult.Cancelled(snapshot(stdoutBuilder), snapshot(stderrBuilder)))
		}
		// The command's coroutine stops the process and its group as it ends.
		scope.cancel()
	}

	override fun isRunning(): Boolean = process?.isAlive == true

	companion object {
		private const val MAX_OUTPUT_BYTES = 10 * 1024 * 1024

		/** How long output may stay open once the command has exited. */
		const val OUTPUT_GRACE_MS = 500L

		/** How long a reader may take to end once the command is stopped and its output closed. */
		private const val READERS_STOP_MS = 500L
	}
}

private fun closeQuietly(vararg closeables: Closeable) {
	for (closeable in closeables) {
		try {
			closeable.close()
		} catch (e: IOException) {
			// An InterruptedIOException here (ADFA-3945) only means a read was cut short.
		}
	}
}

private class RejectedCommandExecution(
	override val executionId: String,
	error: String,
) : CommandExecution {
	private val result = CommandResult.Failure(-1, "", "", error, 0)

	override val output: Flow<CommandOutput> = emptyFlow()

	override suspend fun await(): CommandResult = result

	override fun cancel() = Unit
}
