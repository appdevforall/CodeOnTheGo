package com.itsaky.androidide.plugins.manager.services

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.lookup.Lookup
import com.itsaky.androidide.projects.builder.BuildService
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
		private val result: () -> CompletableFuture<TaskExecutionResult>,
	) : BuildService {
		val executed = mutableListOf<List<String>>()

		override fun isToolingServerStarted() = serverStarted

		override fun executeTasks(tasks: List<String>): CompletableFuture<TaskExecutionResult> {
			executed += tasks
			return result()
		}

		override fun metadata(): CompletableFuture<ToolingServerMetadata> = unsupported()

		override fun initializeProject(params: InitializeProjectParams): CompletableFuture<InitializeResult> = unsupported()

		override fun executeTasks(message: TaskExecutionMessage): CompletableFuture<TaskExecutionResult> = unsupported()

		override fun cancelCurrentBuild(): CompletableFuture<BuildCancellationRequestResult> = unsupported()

		private fun unsupported(): Nothing = throw UnsupportedOperationException()
	}

	private fun register(service: FakeBuildService) = service.also { Lookup.getDefault().register(BuildService.KEY_BUILD_SERVICE, it) }

	private fun execute(): Boolean =
		IdeBuildServiceImpl
			.getInstance()
			.executeTasks(":app:assembleDebug")
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
}
