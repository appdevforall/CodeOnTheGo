package com.itsaky.androidide.quickbuild

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.lookup.Lookup
import com.itsaky.androidide.projects.builder.BuildService
import com.itsaky.androidide.resources.R
import com.itsaky.androidide.tooling.api.messages.InitializeProjectParams
import com.itsaky.androidide.tooling.api.messages.TaskExecutionMessage
import com.itsaky.androidide.tooling.api.messages.result.BuildCancellationRequestResult
import com.itsaky.androidide.tooling.api.messages.result.InitializeResult
import com.itsaky.androidide.tooling.api.messages.result.TaskExecutionResult
import com.itsaky.androidide.tooling.api.models.ToolingServerMetadata
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.appdevforall.cotg.quickbuild.domain.session.QuickBuildMessage
import org.appdevforall.cotg.quickbuild.service.provision.ProvisionOutcome
import org.junit.After
import org.junit.Test
import java.util.concurrent.CompletableFuture

/**
 * The single Gradle slot is checked twice: late, immediately before `executeTasks`, where it
 * closes the race, and early, before any of the work a refused build should not pay for.
 *
 * The early check is not redundant with the late one. Everything between them has lasting
 * side effects - staging writes build inputs into the user's project, and the baseline
 * generation is persisted before it is handed out, so a build refused after allocation burns
 * that number permanently. And refusal is the COMMON case, not the exotic one: CoGo's project
 * sync fires on exactly the gradle-file edit that invalidates a Quick Build session.
 */
class GradleQuickBuildProvisionerSlotTest {
	private var stageCalls = 0
	private var generationCalls = 0

	@After
	fun tearDown() {
		Lookup.getDefault().unregister(BuildService.KEY_BUILD_SERVICE)
	}

	@Test
	fun `a busy Gradle slot burns no baseline generation and stages nothing`() =
		runTest {
			Lookup.getDefault().update(BuildService.KEY_BUILD_SERVICE, FakeBuildService(inProgress = true))

			val outcome = provisioner().provision()

			assertThat(outcome).isInstanceOf(ProvisionOutcome.Failure::class.java)
			// "Setup failed" sends the user looking for a fault in their project. Nothing is
			// wrong with it; another build holds the slot and the remedy is to wait.
			assertThat((outcome as ProvisionOutcome.Failure).message)
				.isEqualTo(QuickBuildMessage.Literal(SLOT_BUSY_COPY))
			assertThat(stageCalls).isEqualTo(0)
			assertThat(generationCalls).isEqualTo(0)
		}

	private fun provisioner(): GradleQuickBuildProvisioner {
		val context =
			mockk<Context>(relaxed = true) {
				every { getString(R.string.quick_build_slot_busy) } returns SLOT_BUSY_COPY
			}
		return GradleQuickBuildProvisioner(
			context = context,
			paths = EnvironmentQuickBuildPaths(context),
			installer = mockk(relaxed = true),
			packages = mockk(relaxed = true),
			nextBaselineGeneration = {
				generationCalls++
				1L
			},
			stage = { _, _ -> stageCalls++ },
		)
	}

	/** Only the in-progress flag matters here; nothing else may be reached. */
	private class FakeBuildService(
		private val inProgress: Boolean,
	) : BuildService {
		override val isBuildInProgress: Boolean
			get() = inProgress

		override val isUserVisibleBuildInProgress: Boolean
			get() = false

		override fun isToolingServerStarted(): Boolean = true

		override fun metadata(): CompletableFuture<ToolingServerMetadata> = CompletableFuture()

		override fun initializeProject(params: InitializeProjectParams): CompletableFuture<InitializeResult> = CompletableFuture()

		override fun executeTasks(tasks: List<String>): CompletableFuture<TaskExecutionResult> =
			throw AssertionError("a refused build must never reach executeTasks")

		override fun executeTasks(message: TaskExecutionMessage): CompletableFuture<TaskExecutionResult> =
			throw AssertionError("a refused build must never reach executeTasks")

		override fun cancelCurrentBuild(): CompletableFuture<BuildCancellationRequestResult> = CompletableFuture()
	}

	private companion object {
		const val SLOT_BUSY_COPY = "Another build is running."
	}
}
