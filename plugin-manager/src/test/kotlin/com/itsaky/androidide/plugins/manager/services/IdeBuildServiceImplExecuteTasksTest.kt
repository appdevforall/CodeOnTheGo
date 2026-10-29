package com.itsaky.androidide.plugins.manager.services

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.lookup.Lookup
import com.itsaky.androidide.plugins.services.GradleTaskResult
import com.itsaky.androidide.projects.builder.BuildService
import com.itsaky.androidide.tooling.api.messages.BuildId
import com.itsaky.androidide.tooling.api.messages.BuildRunType
import com.itsaky.androidide.tooling.api.messages.InitializeProjectParams
import com.itsaky.androidide.tooling.api.messages.TaskExecutionMessage
import com.itsaky.androidide.tooling.api.messages.result.BuildCancellationRequestResult
import com.itsaky.androidide.tooling.api.messages.result.InitializeResult
import com.itsaky.androidide.tooling.api.messages.result.TaskExecutionResult
import com.itsaky.androidide.tooling.api.models.ToolingServerMetadata
import org.junit.After
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class IdeBuildServiceImplExecuteTasksTest {
	private class FakeBuildService(
		private val serverStarted: Boolean = true,
		override val isBuildInProgress: Boolean = false,
		private val cancellation: () -> CompletableFuture<BuildCancellationRequestResult> = { error("must not cancel") },
		private val result: () -> CompletableFuture<TaskExecutionResult>,
	) : BuildService {
		override var currentBuildId: BuildId? = null
		val executed = mutableListOf<List<String>>()
		val messages = mutableListOf<TaskExecutionMessage>()
		var cancelRequests = 0

		override fun isToolingServerStarted() = serverStarted

		override fun executeTasks(tasks: List<String>): CompletableFuture<TaskExecutionResult> = unsupported()

		override fun executeTasks(message: TaskExecutionMessage): CompletableFuture<TaskExecutionResult> {
			executed += message.tasks
			messages += message
			return result()
		}

		override fun nextBuildId(runType: BuildRunType) = BuildId("session", ++lastBuildId, runType)

		private var lastBuildId = 0L

		override fun cancelCurrentBuild(): CompletableFuture<BuildCancellationRequestResult> {
			cancelRequests++
			return cancellation()
		}

		override fun metadata(): CompletableFuture<ToolingServerMetadata> = unsupported()

		override fun initializeProject(params: InitializeProjectParams): CompletableFuture<InitializeResult> = unsupported()

		private fun unsupported(): Nothing = throw UnsupportedOperationException()
	}

	private fun register(service: FakeBuildService) = service.also { Lookup.getDefault().register(BuildService.KEY_BUILD_SERVICE, it) }

	private fun execute(vararg tasks: String = arrayOf(":app:assembleDebug")): Boolean =
		IdeBuildServiceImpl
			.getInstance()
			.executeTasks(*tasks)
			.get(5, TimeUnit.SECONDS)

	private fun run(
		tasks: List<String> = listOf(":app:testDebugUnitTest"),
		arguments: List<String> = emptyList(),
	): GradleTaskResult =
		IdeBuildServiceImpl
			.getInstance()
			.executeTasks(tasks, arguments)
			.get(5, TimeUnit.SECONDS)

	private fun cancel(): Boolean =
		IdeBuildServiceImpl
			.getInstance()
			.cancelBuild()
			.get(5, TimeUnit.SECONDS)

	@After
	fun tearDown() {
		Lookup.getDefault().unregister(BuildService.KEY_BUILD_SERVICE)
	}

	@Test
	fun successfulBuildCompletesTrue() {
		val service = register(FakeBuildService { CompletableFuture.completedFuture(TaskExecutionResult.SUCCESS) })

		assertThat(execute()).isTrue()
		assertThat(service.executed).containsExactly(listOf(":app:assembleDebug"))
	}

	@Test
	fun failedBuildCompletesFalse() {
		register(
			FakeBuildService {
				CompletableFuture.completedFuture(TaskExecutionResult(false, TaskExecutionResult.Failure.BUILD_FAILED))
			},
		)

		assertThat(execute()).isFalse()
	}

	@Test
	fun nullBuildResultCompletesFalse() {
		register(FakeBuildService { CompletableFuture.completedFuture(null) })

		assertThat(execute()).isFalse()
	}

	@Test
	fun exceptionalBuildCompletesFalse() {
		register(
			FakeBuildService {
				CompletableFuture<TaskExecutionResult>().apply { completeExceptionally(IllegalStateException("gradle died")) }
			},
		)

		assertThat(execute()).isFalse()
	}

	@Test
	fun missingBuildServiceCompletesFalse() {
		assertThat(execute()).isFalse()
	}

	@Test
	fun stoppedToolingServerCompletesFalseWithoutExecuting() {
		val service = register(FakeBuildService(serverStarted = false) { error("must not execute") })

		assertThat(execute()).isFalse()
		assertThat(service.executed).isEmpty()
	}

	@Test
	fun buildInProgressCompletesFalseWithoutExecuting() {
		val service = register(FakeBuildService(isBuildInProgress = true) { error("must not execute") })

		assertThat(execute()).isFalse()
		assertThat(service.executed).isEmpty()
	}

	@Test
	fun argumentsReachTheToolingServer() {
		val service = register(FakeBuildService { CompletableFuture.completedFuture(TaskExecutionResult.SUCCESS) })

		val result = run(arguments = listOf("--tests", "com.example.FooTest", "-Pflag=true"))

		assertThat(result).isEqualTo(GradleTaskResult.Success)
		assertThat(service.messages.single().tasks).containsExactly(":app:testDebugUnitTest")
		assertThat(
			service.messages
				.single()
				.buildParams.gradleArgs,
		).containsExactly("--tests", "com.example.FooTest", "-Pflag=true")
			.inOrder()
	}

	@Test
	fun failedBuildReportsTheFailure() {
		register(
			FakeBuildService {
				CompletableFuture.completedFuture(TaskExecutionResult(false, TaskExecutionResult.Failure.BUILD_FAILED))
			},
		)

		assertThat(run()).isEqualTo(GradleTaskResult.Failed("BUILD_FAILED"))
	}

	@Test
	fun cancelledBuildReportsCancelled() {
		register(
			FakeBuildService {
				CompletableFuture.completedFuture(TaskExecutionResult(false, TaskExecutionResult.Failure.BUILD_CANCELLED))
			},
		)

		assertThat(run()).isEqualTo(GradleTaskResult.Cancelled)
	}

	@Test
	fun exceptionalBuildReportsTheCause() {
		register(
			FakeBuildService {
				CompletableFuture<TaskExecutionResult>().apply { completeExceptionally(IllegalStateException("gradle died")) }
			},
		)

		assertThat(run()).isEqualTo(GradleTaskResult.Failed("gradle died"))
	}

	@Test
	fun buildInProgressIsRefusedWithTheReason() {
		val service = register(FakeBuildService(isBuildInProgress = true) { error("must not execute") })

		assertThat(run()).isEqualTo(GradleTaskResult.Refused("another build is in progress"))
		assertThat(service.executed).isEmpty()
	}

	@Test
	fun buildThatTakesTheSlotAfterTheCheckIsRefused() {
		register(
			FakeBuildService {
				CompletableFuture.completedFuture(TaskExecutionResult(false, TaskExecutionResult.Failure.BUILD_IN_PROGRESS))
			},
		)

		assertThat(run()).isEqualTo(GradleTaskResult.Refused("another build is in progress"))
	}

	@Test
	fun stoppedToolingServerIsRefusedWithTheReason() {
		val service = register(FakeBuildService(serverStarted = false) { error("must not execute") })

		assertThat(run()).isEqualTo(GradleTaskResult.Refused("tooling server is not started"))
		assertThat(service.executed).isEmpty()
	}

	@Test
	fun missingBuildServiceIsRefused() {
		assertThat(run()).isEqualTo(GradleTaskResult.Refused("build service is not registered"))
	}

	@Test
	fun noTasksIsRefusedWithoutExecuting() {
		val service = register(FakeBuildService { error("must not execute") })

		assertThat(run(tasks = emptyList())).isInstanceOf(GradleTaskResult.Refused::class.java)
		assertThat(service.executed).isEmpty()
	}

	@Test
	fun cancelBuildReportsAnEnqueuedCancellation() {
		val service =
			register(
				FakeBuildService(
					cancellation = { CompletableFuture.completedFuture(BuildCancellationRequestResult(true)) },
				) { error("must not execute") },
			)

		assertThat(cancel()).isTrue()
		assertThat(service.cancelRequests).isEqualTo(1)
	}

	@Test
	fun cancelBuildReportsFalseWhenNothingIsRunning() {
		register(
			FakeBuildService(
				cancellation = {
					CompletableFuture.completedFuture(
						BuildCancellationRequestResult(false, BuildCancellationRequestResult.Reason.NO_RUNNING_BUILD),
					)
				},
			) { error("must not execute") },
		)

		assertThat(cancel()).isFalse()
	}

	@Test
	fun cancelBuildReportsFalseWithoutAToolingServer() {
		val service = register(FakeBuildService(serverStarted = false) { error("must not execute") })

		assertThat(cancel()).isFalse()
		assertThat(service.cancelRequests).isEqualTo(0)
	}

	@Test
	fun cancelBuildReportsFalseWithoutABuildService() {
		assertThat(cancel()).isFalse()
	}

	@Test
	fun optionLikeTaskNameIsRefusedWithoutExecuting() {
		val service = register(FakeBuildService { error("must not execute") })

		assertThat(run(tasks = listOf("help", "-I"))).isEqualTo(GradleTaskResult.Refused("'-I' is not a task"))
		assertThat(execute("--init-script", "/tmp/evil.gradle")).isFalse()
		assertThat(service.executed).isEmpty()
	}

	@Test
	fun runOutputHoldsOnlyTheLinesPrintedWhileItRan() {
		val pending = CompletableFuture<TaskExecutionResult>()
		register(FakeBuildService { pending })
		val impl = IdeBuildServiceImpl.getInstance()
		impl.onBuildOutput("an earlier build")

		val run = impl.startTasks(listOf(":app:test"), emptyList())
		impl.onBuildOutput("> Task :app:test\n")
		impl.onBuildOutput("\n")
		impl.onBuildOutput("BUILD SUCCESSFUL\n")
		pending.complete(TaskExecutionResult.SUCCESS)
		run.result.get(5, TimeUnit.SECONDS)
		impl.onBuildOutput("a later build")

		assertThat(run.output()).isEqualTo("> Task :app:test\n\nBUILD SUCCESSFUL")
	}

	@Test
	fun runRefusedAtTheSlotClaimHasNoOutput() {
		val pending = CompletableFuture<TaskExecutionResult>()
		register(FakeBuildService { pending })
		val impl = IdeBuildServiceImpl.getInstance()

		val run = impl.startTasks(listOf(":app:test"), emptyList())
		impl.onBuildOutput("the other build's line")
		pending.complete(TaskExecutionResult(false, TaskExecutionResult.Failure.BUILD_IN_PROGRESS))
		run.result.get(5, TimeUnit.SECONDS)

		assertThat(run.output()).isEmpty()
	}

	@Test
	fun runCancelsItsOwnBuild() {
		val service =
			register(
				FakeBuildService(
					cancellation = { CompletableFuture.completedFuture(BuildCancellationRequestResult(true)) },
				) { CompletableFuture() },
			)
		val run = IdeBuildServiceImpl.getInstance().startTasks(listOf(":app:test"), emptyList())
		service.currentBuildId = service.messages.single().buildId

		assertThat(run.cancel().get(5, TimeUnit.SECONDS)).isTrue()
		assertThat(service.cancelRequests).isEqualTo(1)
	}

	@Test
	fun runLeavesAnotherBuildAlone() {
		val service = register(FakeBuildService { CompletableFuture() })
		val run = IdeBuildServiceImpl.getInstance().startTasks(listOf(":app:test"), emptyList())
		// The run's slot was released and someone else's build took it.
		service.currentBuildId = BuildId("session", 99, BuildRunType.ProjectSync)

		assertThat(run.cancel().get(5, TimeUnit.SECONDS)).isFalse()
		assertThat(service.cancelRequests).isEqualTo(0)
	}
}
