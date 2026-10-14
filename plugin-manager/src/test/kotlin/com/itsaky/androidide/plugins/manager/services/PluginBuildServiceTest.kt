package com.itsaky.androidide.plugins.manager.services

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.plugins.PluginPermission
import com.itsaky.androidide.plugins.services.BuildStatusListener
import com.itsaky.androidide.plugins.services.GradleTaskResult
import com.itsaky.androidide.plugins.services.IdeBuildService
import org.junit.Test
import java.util.concurrent.CompletableFuture

class PluginBuildServiceTest {
	private class FakeIdeBuildService : IdeBuildService {
		val runs = mutableListOf<Pair<List<String>, List<String>>>()
		var cancelRequests = 0

		override fun executeTasks(
			tasks: List<String>,
			arguments: List<String>,
		): CompletableFuture<GradleTaskResult> {
			runs += tasks to arguments
			return CompletableFuture.completedFuture(GradleTaskResult.Success)
		}

		override fun cancelBuild(): CompletableFuture<Boolean> {
			cancelRequests++
			return CompletableFuture.completedFuture(true)
		}

		override fun isBuildInProgress() = false

		override fun isToolingServerStarted() = true

		override fun addBuildStatusListener(callback: BuildStatusListener) = Unit

		override fun removeBuildStatusListener(callback: BuildStatusListener) = Unit
	}

	private val delegate = FakeIdeBuildService()

	private fun service(vararg permissions: PluginPermission) = PluginBuildService("test.plugin", permissions.toSet(), delegate)

	@Test
	fun tasksWithoutArgumentsNeedNoPermission() {
		service().executeTasks(listOf(":app:test"), emptyList())

		assertThat(delegate.runs).containsExactly(listOf(":app:test") to emptyList<String>())
	}

	@Test
	fun argumentsNeedSystemCommands() {
		val error =
			runCatching { service().executeTasks(listOf("help"), listOf("--init-script", "/tmp/evil.gradle")) }
				.exceptionOrNull()

		assertThat(error).isInstanceOf(SecurityException::class.java)
		assertThat(delegate.runs).isEmpty()
	}

	@Test
	fun argumentsRunWithSystemCommands() {
		service(PluginPermission.SYSTEM_COMMANDS).executeTasks(listOf(":app:test"), listOf("--info"))

		assertThat(delegate.runs).containsExactly(listOf(":app:test") to listOf("--info"))
	}

	@Test
	fun cancelBuildNeedsSystemCommands() {
		assertThat(runCatching { service().cancelBuild() }.exceptionOrNull()).isInstanceOf(SecurityException::class.java)
		assertThat(delegate.cancelRequests).isEqualTo(0)

		service(PluginPermission.SYSTEM_COMMANDS).cancelBuild()
		assertThat(delegate.cancelRequests).isEqualTo(1)
	}
}
