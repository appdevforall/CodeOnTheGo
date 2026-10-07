package com.itsaky.androidide.plugins.manager.services

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.plugins.PluginPermission
import com.itsaky.androidide.plugins.extensions.CommandOutput
import com.itsaky.androidide.plugins.extensions.CommandResult
import com.itsaky.androidide.plugins.extensions.CommandSpec
import com.itsaky.androidide.plugins.services.GradleTaskResult
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.io.File
import java.util.concurrent.CompletableFuture

class IdeCommandServiceImplGradleTaskTest {
	private class FakeRun(
		override val result: CompletableFuture<GradleTaskResult>,
		private val output: String?,
		private val onCancel: () -> Unit,
	) : GradleTaskRun {
		override fun output(): String = output.orEmpty()

		override fun cancel(): CompletableFuture<Boolean> {
			onCancel()
			return CompletableFuture.completedFuture(true)
		}
	}

	private class FakeRunner(
		private val buildOutput: String? = "> Task :app:test\nBUILD SUCCESSFUL",
		private val onCancel: (CompletableFuture<GradleTaskResult>) -> Unit = {},
		private val result: () -> CompletableFuture<GradleTaskResult>,
	) : GradleTaskRunner {
		val runs = mutableListOf<Pair<List<String>, List<String>>>()
		val started = mutableListOf<FakeRun>()
		var cancelRequests = 0

		override fun start(
			tasks: List<String>,
			arguments: List<String>,
		): GradleTaskRun {
			runs += tasks to arguments
			val future = result()
			return FakeRun(future, buildOutput) {
				cancelRequests++
				onCancel(future)
			}.also { started += it }
		}
	}

	private val spec = CommandSpec.GradleTask(":app:testDebugUnitTest", listOf("--tests", "com.example.FooTest"))

	private fun service(
		runner: GradleTaskRunner,
		permissions: Set<PluginPermission> = setOf(PluginPermission.SYSTEM_COMMANDS),
	) = IdeCommandServiceImpl(
		pluginId = "test.plugin",
		permissions = permissions,
		// No project and no gradlew: the tooling-server path needs neither.
		projectRootProvider = { null },
		appFilesDir = File("unused"),
		gradleTaskRunner = runner,
	)

	private fun <T> await(block: suspend () -> T): T = runBlocking { withTimeout(5_000) { block() } }

	@Test
	fun gradleTaskRunsOnTheToolingServerWithItsArguments() {
		val build = FakeRunner { CompletableFuture.completedFuture(GradleTaskResult.Success) }

		val result = await { service(build).executeCommand(spec).await() }

		assertThat(build.runs)
			.containsExactly(listOf(":app:testDebugUnitTest") to listOf("--tests", "com.example.FooTest"))
		assertThat(result).isInstanceOf(CommandResult.Success::class.java)
		result as CommandResult.Success
		assertThat(result.exitCode).isEqualTo(0)
		assertThat(result.stdout).isEqualTo("> Task :app:test\nBUILD SUCCESSFUL")
	}

	@Test
	fun outputFlowCarriesTheBuildOutputThenTheExitCode() {
		val build = FakeRunner { CompletableFuture.completedFuture(GradleTaskResult.Success) }

		val output = await { service(build).executeCommand(spec).output.toList() }

		assertThat(output)
			.containsExactly(
				CommandOutput.StdOut("> Task :app:test"),
				CommandOutput.StdOut("BUILD SUCCESSFUL"),
				CommandOutput.ExitCode(0),
			).inOrder()
	}

	@Test
	fun outputFlowKeepsBlankLinesButNotTheTrailingNewline() {
		val build =
			FakeRunner(buildOutput = "FAILURE\n\n* What went wrong:\n") {
				CompletableFuture.completedFuture(GradleTaskResult.Success)
			}

		val output = await { service(build).executeCommand(spec).output.toList() }

		assertThat(output)
			.containsExactly(
				CommandOutput.StdOut("FAILURE"),
				CommandOutput.StdOut(""),
				CommandOutput.StdOut("* What went wrong:"),
				CommandOutput.ExitCode(0),
			).inOrder()
	}

	@Test
	fun failedBuildIsAFailureWithExitCodeOne() {
		val build = FakeRunner { CompletableFuture.completedFuture(GradleTaskResult.Failed("BUILD_FAILED")) }

		val result = await { service(build).executeCommand(spec).await() } as CommandResult.Failure

		assertThat(result.exitCode).isEqualTo(1)
		assertThat(result.error).isEqualTo("BUILD_FAILED")
	}

	@Test
	fun refusedBuildIsAFailureCarryingTheReason() {
		val build =
			FakeRunner { CompletableFuture.completedFuture(GradleTaskResult.Refused("another build is in progress")) }

		val result = await { service(build).executeCommand(spec).await() } as CommandResult.Failure

		assertThat(result.exitCode).isEqualTo(-1)
		assertThat(result.error).isEqualTo("another build is in progress")
		assertThat(result.stdout).isEmpty()
	}

	@Test
	fun cancellingARunningTaskCompletesWhenTheBuildStops() {
		val build = FakeRunner(onCancel = { it.complete(GradleTaskResult.Cancelled) }) { CompletableFuture() }
		val service = service(build)
		val execution = service.executeCommand(spec)

		assertThat(service.isCommandRunning(execution.executionId)).isTrue()
		execution.cancel()

		assertThat(build.cancelRequests).isEqualTo(1)
		val result = await { execution.await() } as CommandResult.Cancelled
		// The run's own output, not an empty placeholder.
		assertThat(result.partialStdout).isEqualTo("> Task :app:test\nBUILD SUCCESSFUL")
		assertThat(service.getRunningCommandCount()).isEqualTo(0)
	}

	@Test
	fun cancelledTaskStaysRunningUntilTheBuildStops() {
		val build = FakeRunner { CompletableFuture() }
		val service = service(build)
		val execution = service.executeCommand(spec)

		execution.cancel()

		// The build still holds the slot, so the command is not over.
		assertThat(service.isCommandRunning(execution.executionId)).isTrue()
		build.started
			.single()
			.result
			.complete(GradleTaskResult.Cancelled)
		assertThat(await { execution.await() }).isInstanceOf(CommandResult.Cancelled::class.java)
	}

	@Test
	fun cancelledCommandsFreeTheirConcurrencySlots() {
		// The build never completes, so the completion callback never fires.
		val build = FakeRunner { CompletableFuture() }
		val service = service(build)

		repeat(3) {
			val execution = service.executeCommand(spec)
			assertThat(service.cancelCommand(execution.executionId)).isTrue()
		}

		assertThat(service.getRunningCommandCount()).isEqualTo(0)
		service.executeCommand(spec)
	}

	@Test
	fun cancellingAFinishedTaskLeavesLaterBuildsAlone() {
		val build = FakeRunner { CompletableFuture.completedFuture(GradleTaskResult.Success) }
		val execution = service(build).executeCommand(spec)
		await { execution.await() }

		execution.cancel()

		assertThat(build.cancelRequests).isEqualTo(0)
	}

	@Test
	fun timeoutCancelsTheBuildAndReportsATimeout() {
		val build = FakeRunner(onCancel = { it.complete(GradleTaskResult.Cancelled) }) { CompletableFuture() }

		val result = await { service(build).executeCommand(spec, timeoutMs = 50).await() } as CommandResult.Failure

		assertThat(build.cancelRequests).isEqualTo(1)
		assertThat(result.error).isEqualTo("Gradle task timed out after 50ms")
	}

	@Test
	fun timeoutCompletesEvenIfGradleIgnoresTheCancel() {
		val build = FakeRunner { CompletableFuture() }
		val execution =
			GradleTaskExecution("id", spec, build, timeoutMs = 50, stopGraceMs = 50).apply { start {} }

		val result = await { execution.await() } as CommandResult.Failure

		assertThat(result.error).contains("did not stop")
		assertThat(result.stdout).isEqualTo("> Task :app:test\nBUILD SUCCESSFUL")
	}

	@Test
	fun cancelCompletesEvenIfGradleIgnoresIt() {
		val build = FakeRunner { CompletableFuture() }
		val execution =
			GradleTaskExecution("id", spec, build, timeoutMs = 60_000, stopGraceMs = 50).apply { start {} }

		execution.cancel()

		assertThat(await { execution.await() }).isInstanceOf(CommandResult.Cancelled::class.java)
	}

	@Test(expected = SecurityException::class)
	fun gradleTaskStillRequiresSystemCommands() {
		val build = FakeRunner { error("must not run") }

		service(build, permissions = emptySet()).executeCommand(spec)
	}
}
