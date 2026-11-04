package com.itsaky.androidide.plugins.manager.services

import com.itsaky.androidide.plugins.extensions.CommandOutput
import com.itsaky.androidide.plugins.extensions.CommandResult
import com.itsaky.androidide.plugins.extensions.CommandSpec
import com.itsaky.androidide.plugins.services.GradleTaskResult
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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A [CommandSpec.GradleTask] run on the IDE's tooling server. Its output is emitted once the build
 * ends, from the lines the run captured for itself.
 *
 * A cancel or timeout asks Gradle to stop and completes when the build does. If Gradle has not
 * stopped [stopGraceMs] later, the command completes anyway, though the build may still hold the
 * slot.
 */
internal class GradleTaskExecution(
	override val executionId: String,
	private val spec: CommandSpec.GradleTask,
	private val runner: GradleTaskRunner,
	private val timeoutMs: Long,
	private val stopGraceMs: Long = STOP_GRACE_MS,
) : RunningCommand {
	private val outputChannel = Channel<CommandOutput>(capacity = Channel.UNLIMITED)
	private val resultDeferred = CompletableDeferred<CommandResult>()
	private val scope = CoroutineScope(Dispatchers.IO + Job())
	private val finished = AtomicBoolean(false)
	private var startTime = 0L

	@Volatile
	private var timedOut = false

	@Volatile
	private var run: GradleTaskRun? = null

	@Volatile
	private var onComplete: () -> Unit = {}

	override val output: Flow<CommandOutput> = outputChannel.receiveAsFlow()

	override fun start(onComplete: () -> Unit) {
		this.onComplete = onComplete
		startTime = System.currentTimeMillis()
		val run = runner.start(listOf(spec.taskPath), spec.arguments)
		this.run = run
		scope.launch {
			delay(timeoutMs)
			timedOut = true
			stop(run) { CommandResult.Failure(-1, it, "", "Gradle task timed out after ${timeoutMs}ms and did not stop", duration()) }
		}
		run.result.whenComplete { result, error -> finish(toCommandResult(result, error, run.output())) }
	}

	private fun toCommandResult(
		result: GradleTaskResult?,
		error: Throwable?,
		output: String,
	): CommandResult =
		when {
			error != null -> {
				CommandResult.Failure(-1, output, "", error.message, duration())
			}

			result == GradleTaskResult.Success -> {
				CommandResult.Success(0, output, "", duration())
			}

			result == GradleTaskResult.Cancelled && timedOut -> {
				CommandResult.Failure(
					-1,
					output,
					"",
					"Gradle task timed out after ${timeoutMs}ms",
					duration(),
				)
			}

			result == GradleTaskResult.Cancelled -> {
				CommandResult.Cancelled(output, "")
			}

			result is GradleTaskResult.Refused -> {
				CommandResult.Failure(-1, "", "", result.reason, duration())
			}

			result is GradleTaskResult.Failed -> {
				CommandResult.Failure(1, output, "", result.reason, duration())
			}

			else -> {
				CommandResult.Failure(-1, output, "", "No result", duration())
			}
		}

	private fun duration() = System.currentTimeMillis() - startTime

	/** Asks Gradle to stop [run]; completes with [ifNotStopped] if the build outlives the grace period. */
	private fun stop(
		run: GradleTaskRun,
		ifNotStopped: (output: String) -> CommandResult,
	) {
		if (run.result.isDone) return
		run.cancel()
		scope.launch {
			delay(stopGraceMs)
			finish(ifNotStopped(run.output()))
		}
	}

	private fun finish(result: CommandResult) {
		if (!finished.compareAndSet(false, true)) return
		scope.cancel()
		val (stdout, exitCode) =
			when (result) {
				is CommandResult.Success -> result.stdout to result.exitCode
				is CommandResult.Failure -> result.stdout to result.exitCode
				is CommandResult.Cancelled -> result.partialStdout to null
			}
		// Blank lines are kept (they separate stack traces); only the final newline's empty tail goes.
		if (stdout.isNotEmpty()) {
			stdout.removeSuffix("\n").lineSequence().forEach { outputChannel.trySend(CommandOutput.StdOut(it)) }
		}
		exitCode?.let { outputChannel.trySend(CommandOutput.ExitCode(it)) }
		outputChannel.close()
		resultDeferred.complete(result)
		onComplete()
	}

	override suspend fun await(): CommandResult = resultDeferred.await()

	override fun cancel() {
		val run = run ?: return finish(CommandResult.Cancelled("", ""))
		// Completes with Cancelled once the build stops, so the slot is free by then.
		stop(run) { CommandResult.Cancelled(it, "") }
	}

	override fun isRunning(): Boolean = !finished.get()

	companion object {
		private const val STOP_GRACE_MS = 15_000L
	}
}
