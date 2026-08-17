package com.itsaky.androidide.quickbuild

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.lookup.Lookup
import com.itsaky.androidide.projects.builder.BuildService
import com.itsaky.androidide.tooling.api.messages.InitializeProjectParams
import com.itsaky.androidide.tooling.api.messages.TaskExecutionMessage
import com.itsaky.androidide.tooling.api.messages.result.BuildCancellationRequestResult
import com.itsaky.androidide.tooling.api.messages.result.InitializeResult
import com.itsaky.androidide.tooling.api.messages.result.TaskExecutionResult
import com.itsaky.androidide.tooling.api.models.ToolingServerMetadata
import io.mockk.mockk
import org.junit.After
import org.junit.Test
import java.io.File
import java.util.concurrent.CompletableFuture

/**
 * Two behaviours of [GradleQuickBuildProvisioner] that are one edit away from breaking something
 * the user would blame on Quick Build, and that nothing else pins.
 *
 * The device has a single Gradle cancellation token, so a stop-tap on Quick Build's own build is
 * indistinguishable at the tooling API from a stop-tap on the user's Standard Run - and the
 * session issues one whenever it tears down.
 */
class GradleQuickBuildProvisionerCancelTest {
	@After
	fun tearDown() {
		Lookup.getDefault().unregister(BuildService.KEY_BUILD_SERVICE)
	}

	@Test
	fun `a cancel is refused while the in-flight build is the user's own`() {
		val service = register(FakeBuildService(inProgress = true, userVisible = true))

		val cancelled = provisioner().cancelProxyAppBuild()

		assertThat(cancelled).isFalse()
		// The load-bearing assertion: the user's build was never asked to stop.
		assertThat(service.cancelCalls).isEqualTo(0)
	}

	@Test
	fun `a cancel goes through for Quick Build's own internal build`() {
		val service = register(FakeBuildService(inProgress = true, userVisible = false))

		val cancelled = provisioner().cancelProxyAppBuild()

		assertThat(cancelled).isTrue()
		assertThat(service.cancelCalls).isEqualTo(1)
	}

	@Test
	fun `nothing in flight cancels nothing`() {
		val service = register(FakeBuildService(inProgress = false, userVisible = false))

		assertThat(provisioner().cancelProxyAppBuild()).isFalse()
		assertThat(service.cancelCalls).isEqualTo(0)
	}

	@Test
	fun `a nested gradle path maps to nested directories, not one colon-named directory`() {
		val root = File("/projects/demo")

		val nested = moduleDir(root, ":feature:home")

		assertThat(nested).isEqualTo(File(root, "feature/home"))
		// A separator that stayed ':' would produce <root>/feature:home - one directory whose
		// name contains a colon, which exists nowhere, so setup.json is never found and the
		// session fails with "proxy app build failed" on every multi-module project.
		assertThat(nested.path).doesNotContain(":")
	}

	@Test
	fun `a top-level module and the root project map as expected`() {
		val root = File("/projects/demo")

		assertThat(moduleDir(root, ":app")).isEqualTo(File(root, "app"))
		assertThat(moduleDir(root, ":")).isEqualTo(root)
		assertThat(moduleDir(root, "")).isEqualTo(root)
	}

	private fun register(service: FakeBuildService): FakeBuildService {
		Lookup.getDefault().update(BuildService.KEY_BUILD_SERVICE, service)
		return service
	}

	private fun provisioner(): GradleQuickBuildProvisioner {
		val context = mockk<Context>(relaxed = true)
		return GradleQuickBuildProvisioner(
			context = context,
			paths = EnvironmentQuickBuildPaths(context),
			installer = mockk(relaxed = true),
			packages = mockk(relaxed = true),
		)
	}

	/**
	 * The module-dir derivation is private to the provisioner and needs none of its state, so it
	 * is reached reflectively rather than by widening production visibility for a test.
	 */
	private fun moduleDir(
		projectRoot: File,
		gradlePath: String,
	): File =
		GradleQuickBuildProvisioner::class.java
			.getDeclaredMethod("moduleDir", File::class.java, String::class.java)
			.apply { isAccessible = true }
			.invoke(provisioner(), projectRoot, gradlePath) as File

	/** Only the two in-progress flags and the cancel count matter here. */
	private class FakeBuildService(
		private val inProgress: Boolean,
		private val userVisible: Boolean,
	) : BuildService {
		var cancelCalls = 0
			private set

		override val isBuildInProgress: Boolean
			get() = inProgress

		override val isUserVisibleBuildInProgress: Boolean
			get() = userVisible

		override fun isToolingServerStarted(): Boolean = true

		override fun metadata(): CompletableFuture<ToolingServerMetadata> = CompletableFuture()

		override fun initializeProject(params: InitializeProjectParams): CompletableFuture<InitializeResult> = CompletableFuture()

		override fun executeTasks(tasks: List<String>): CompletableFuture<TaskExecutionResult> = CompletableFuture()

		override fun executeTasks(message: TaskExecutionMessage): CompletableFuture<TaskExecutionResult> = CompletableFuture()

		override fun cancelCurrentBuild(): CompletableFuture<BuildCancellationRequestResult> {
			cancelCalls++
			return CompletableFuture()
		}
	}
}
