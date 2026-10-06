package com.itsaky.androidide.plugins.manager.services

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.plugins.PluginPermission
import com.itsaky.androidide.plugins.extensions.CommandOutput
import com.itsaky.androidide.plugins.extensions.CommandResult
import com.itsaky.androidide.plugins.extensions.CommandSpec
import com.itsaky.androidide.plugins.services.BuildAndLaunchCallback
import com.itsaky.androidide.plugins.services.BuildStatusListener
import com.itsaky.androidide.plugins.services.GradleSyncCallback
import com.itsaky.androidide.plugins.services.GradleTaskResult
import com.itsaky.androidide.plugins.services.IdeBuildService
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.io.File
import java.util.concurrent.CompletableFuture

class IdeCommandServiceImplGradleTaskTest {
	private class FakeIdeBuildService(
		private val buildOutput: String? = "> Task :app:test\nBUILD SUCCESSFUL",
		private val run: () -> CompletableFuture<GradleTaskResult>,
	) : IdeBuildService {
		val runs = mutableListOf<Pair<List<String>, List<String>>>()
		var cancelRequests = 0
		var outputReads = 0

		override fun executeTasks(
			tasks: List<String>,
			arguments: List<String>,
		): CompletableFuture<GradleTaskResult> {
			runs += tasks to arguments
			return run()
		}

		override fun cancelBuild(): CompletableFuture<Boolean> {
			cancelRequests++
			return CompletableFuture.completedFuture(true)
		}

		override fun getBuildOutput(): String? {
			outputReads++
			return buildOutput
		}

		override fun isBuildInProgress() = false

		override fun isToolingServerStarted() = true

		override fun addBuildStatusListener(callback: BuildStatusListener) = Unit

		override fun removeBuildStatusListener(callback: BuildStatusListener) = Unit

		override fun runApp(callback: BuildAndLaunchCallback) = Unit

		override fun triggerGradleSync(callback: GradleSyncCallback) = Unit
	}

	private val spec = CommandSpec.GradleTask(":app:testDebugUnitTest", listOf("--tests", "com.example.FooTest"))

	private fun service(
		buildService: IdeBuildService,
		permissions: Set<PluginPermission> = setOf(PluginPermission.SYSTEM_COMMANDS),
	) = IdeCommandServiceImpl(
		pluginId = "test.plugin",
		permissions = permissions,
		// No project and no gradlew: the tooling-server path needs neither.
		projectRootProvider = { null },
		appFilesDir = File("unused"),
		buildService = buildService,
	)

	private fun <T> await(block: suspend () -> T): T = runBlocking { withTimeout(5_000) { block() } }

	@Test
	fun gradleTaskRunsOnTheToolingServerWithItsArguments() {
		val build = FakeIdeBuildService { CompletableFuture.completedFuture(GradleTaskResult.Success) }

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
		val build = FakeIdeBuildService { CompletableFuture.completedFuture(GradleTaskResult.Success) }

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
			FakeIdeBuildService(buildOutput = "FAILURE\n\n* What went wrong:\n") {
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
		val build = FakeIdeBuildService { CompletableFuture.completedFuture(GradleTaskResult.Failed("BUILD_FAILED")) }

		val result = await { service(build).executeCommand(spec).await() } as CommandResult.Failure

		assertThat(result.exitCode).isEqualTo(1)
		assertThat(result.error).isEqualTo("BUILD_FAILED")
	}

	@Test
	fun refusedBuildIsAFailureCarryingTheReason() {
		val build =
			FakeIdeBuildService { CompletableFuture.completedFuture(GradleTaskResult.Refused("another build is in progress")) }

		val result = await { service(build).executeCommand(spec).await() } as CommandResult.Failure

		assertThat(result.exitCode).isEqualTo(-1)
		assertThat(result.error).isEqualTo("another build is in progress")
		// A refused run produced no output; the pane holds someone else's build.
		assertThat(build.outputReads).isEqualTo(0)
		assertThat(result.stdout).isEmpty()
	}

	@Test
	fun cancellingARunningTaskCancelsTheBuild() {
		val pending = CompletableFuture<GradleTaskResult>()
		val build = FakeIdeBuildService { pending }
		val service = service(build)
		val execution = service.executeCommand(spec)

		assertThat(service.isCommandRunning(execution.executionId)).isTrue()
		execution.cancel()

		assertThat(build.cancelRequests).isEqualTo(1)
		assertThat(await { execution.await() }).isInstanceOf(CommandResult.Cancelled::class.java)
	}

	@Test
	fun cancelledCommandsFreeTheirConcurrencySlots() {
		// The build never completes, so the completion callback never fires.
		val build = FakeIdeBuildService { CompletableFuture() }
		val service = service(build)

		repeat(3) {
			val execution = service.executeCommand(spec)
			assertThat(service.cancelCommand(execution.executionId)).isTrue()
		}

		assertThat(service.getRunningCommandCount()).isEqualTo(0)
		service.executeCommand(spec)
	}

	@Test
	fun cancellingTheExecutionDirectlyFreesItsSlot() {
		val build = FakeIdeBuildService { CompletableFuture() }
		val service = service(build)

		service.executeCommand(spec).cancel()

		assertThat(service.getRunningCommandCount()).isEqualTo(0)
	}

	@Test
	fun cancellingAFinishedTaskLeavesLaterBuildsAlone() {
		val build = FakeIdeBuildService { CompletableFuture.completedFuture(GradleTaskResult.Success) }
		val execution = service(build).executeCommand(spec)
		await { execution.await() }

		execution.cancel()

		assertThat(build.cancelRequests).isEqualTo(0)
	}

	@Test
	fun timeoutCancelsTheBuildAndReportsATimeout() {
		val pending = CompletableFuture<GradleTaskResult>()
		val build =
			object : IdeBuildService by FakeIdeBuildService(run = { pending }) {
				override fun cancelBuild(): CompletableFuture<Boolean> {
					pending.complete(GradleTaskResult.Cancelled)
					return CompletableFuture.completedFuture(true)
				}
			}

		val result = await { service(build).executeCommand(spec, timeoutMs = 50).await() } as CommandResult.Failure

		assertThat(result.error).contains("timed out")
	}

	@Test(expected = SecurityException::class)
	fun gradleTaskStillRequiresSystemCommands() {
		val build = FakeIdeBuildService { error("must not run") }

		service(build, permissions = emptySet()).executeCommand(spec)
	}
}
