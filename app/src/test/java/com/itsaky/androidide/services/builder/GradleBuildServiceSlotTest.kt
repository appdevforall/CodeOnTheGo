package com.itsaky.androidide.services.builder

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.tooling.api.IToolingApiServer
import com.itsaky.androidide.tooling.api.messages.result.TaskExecutionResult
import com.itsaky.androidide.utils.Environment
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * The Gradle build slot when a second build is requested while one runs.
 *
 * The defects this pins: the slot was checked and taken on a pool thread after the request had
 * already been sent, so two callers could both reach the tooling server; and a refused request
 * still cleared the slot when it completed, while the first build was running.
 */
@RunWith(RobolectricTestRunner::class)
class GradleBuildServiceSlotTest {
	@get:Rule
	val tmp = TemporaryFolder()

	private lateinit var service: GradleBuildService
	private val rpc = CompletableFuture<TaskExecutionResult>()

	// A second request that reaches the server is refused there, as ToolingApiServerImpl does.
	private val server =
		mockk<IToolingApiServer> {
			every { executeTasks(any()) } returnsMany
				listOf(rpc, CompletableFuture.failedFuture(IllegalStateException("Build is already in progress")))
		}

	@Before
	fun setUp() {
		Environment.TMP_DIR = tmp.newFolder("tmp")
		service = Robolectric.buildService(GradleBuildService::class.java).get()
		service.onListenerStarted(server, ByteArrayInputStream(ByteArray(0)))
	}

	@Test
	fun `a second build is refused without reaching the tooling server`() {
		service.executeTasks(listOf(":app:assembleDebug"))
		val second = service.executeTasks(listOf(":app:test"))

		assertThat(second.get(5, TimeUnit.SECONDS)).isNull()
		verify(exactly = 1) { server.executeTasks(any()) }
	}

	@Test
	fun `a refused build leaves the running build's slot taken`() {
		val first = service.executeTasks(listOf(":app:assembleDebug"))
		service.executeTasks(listOf(":app:test")).get(5, TimeUnit.SECONDS)

		assertThat(service.isBuildInProgress).isTrue()

		rpc.complete(TaskExecutionResult(isSuccessful = true, failure = null))
		first.get(5, TimeUnit.SECONDS)
		assertThat(service.isBuildInProgress).isFalse()
	}

	@Test
	fun `a request that fails to send frees the slot`() {
		every { server.executeTasks(any()) } throws IllegalStateException("closed")

		runCatching { service.executeTasks(listOf(":app:assembleDebug")) }

		assertThat(service.isBuildInProgress).isFalse()
	}
}
