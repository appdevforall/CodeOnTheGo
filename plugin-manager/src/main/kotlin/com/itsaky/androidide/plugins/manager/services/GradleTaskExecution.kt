package com.itsaky.androidide.plugins.manager.services

import com.itsaky.androidide.plugins.extensions.CommandOutput
import com.itsaky.androidide.plugins.extensions.CommandResult
import com.itsaky.androidide.plugins.extensions.CommandSpec
import com.itsaky.androidide.plugins.services.GradleTaskResult
import com.itsaky.androidide.plugins.services.IdeBuildService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.util.concurrent.CompletableFuture

/**
 * A [CommandSpec.GradleTask] run on the IDE's tooling server. The output is read from the Build
 * Output pane once the build ends, since the tooling server streams it there and nowhere else.
 */
internal class GradleTaskExecution(
	override val executionId: String,
	private val spec: CommandSpec.GradleTask,
	private val buildService: IdeBuildService,
	private val timeoutMs: Long,
) : RunningCommand {
	private val outputChannel = Channel<CommandOutput>(capacity = Channel.UNLIMITED)
	private val resultDeferred = CompletableDeferred<CommandResult>()
	private val scope = CoroutineScope(Dispatchers.IO + Job())

	@Volatile
	private var timedOut = false

	@Volatile
	private var future: CompletableFuture<GradleTaskResult>? = null

	// Also run by cancel(): a build that never completes would otherwise keep its slot.
	@Volatile
	private var onComplete: () -> Unit = {}

	override val output: Flow<CommandOutput> = outputChannel.receiveAsFlow()

	fun start(onComplete: () -> Unit) {
		this.onComplete = onComplete
		val startTime = System.currentTimeMillis()
		val run = buildService.executeTasks(listOf(spec.taskPath), spec.arguments)
		future = run
		scope.launch {
			delay(timeoutMs)
			timedOut = true
			if (!run.isDone) buildService.cancelBuild()
		}
		run.whenComplete { result, error ->
			scope.cancel()
			val duration = System.currentTimeMillis() - startTime
			val buildOutput = if (result is GradleTaskResult.Refused) "" else buildService.getBuildOutput().orEmpty()
			val commandResult =
				when {
					error != null -> {
						CommandResult.Failure(-1, buildOutput, "", error.message, duration)
					}

					result == GradleTaskResult.Success -> {
						CommandResult.Success(0, buildOutput, "", duration)
					}

					result == GradleTaskResult.Cancelled && timedOut -> {
						CommandResult.Failure(-1, buildOutput, "", "Gradle task timed out after ${timeoutMs}ms", duration)
					}

					result == GradleTaskResult.Cancelled -> {
						CommandResult.Cancelled(buildOutput, "")
					}

					result is GradleTaskResult.Refused -> {
						CommandResult.Failure(-1, "", "", result.reason, duration)
					}

					result is GradleTaskResult.Failed -> {
						CommandResult.Failure(1, buildOutput, "", result.reason, duration)
					}

					else -> {
						CommandResult.Failure(-1, buildOutput, "", "No result", duration)
					}
				}
			// Blank lines are kept (they separate stack traces); only the final newline's empty tail goes.
			if (buildOutput.isNotEmpty()) {
				buildOutput.removeSuffix("\n").lineSequence().forEach { outputChannel.trySend(CommandOutput.StdOut(it)) }
			}
			val exitCode =
				when (commandResult) {
					is CommandResult.Success -> commandResult.exitCode
					is CommandResult.Failure -> commandResult.exitCode
					is CommandResult.Cancelled -> null
				}
			exitCode?.let { outputChannel.trySend(CommandOutput.ExitCode(it)) }
			outputChannel.close()
			resultDeferred.complete(commandResult)
			onComplete()
		}
	}

	override suspend fun await(): CommandResult = resultDeferred.await()

	override fun cancel() {
		// Only while this run is the build: once it is done, cancelBuild would stop someone else's.
		if (future?.isDone == false) buildService.cancelBuild()
		scope.cancel()
		outputChannel.close()
		resultDeferred.complete(CommandResult.Cancelled("", ""))
		onComplete()
	}

	override fun isRunning(): Boolean = future?.isDone == false
}
