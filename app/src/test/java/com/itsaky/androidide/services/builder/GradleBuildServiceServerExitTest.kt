package com.itsaky.androidide.services.builder

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.services.ToolingServerNotStartedException
import com.itsaky.androidide.tooling.api.IToolingApiServer
import com.itsaky.androidide.tooling.api.messages.result.TaskExecutionResult
import com.itsaky.androidide.utils.Environment
import io.mockk.every
import io.mockk.mockk
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

/**
 * The Gradle build slot when the tooling server process dies while a build holds it.
 *
 * The defect this pins: [GradleBuildService.isBuildInProgress] was cleared only when the build's
 * RPC future completed, and the RPC layer never completes a request whose server process is gone.
 * On the A56, killing the tooling JVM during Quick Build's on-open prebuild left Run refused with
 * "Quick Build is setting up the app" for the rest of the session, until the project was closed.
 */
@RunWith(RobolectricTestRunner::class)
class GradleBuildServiceServerExitTest {
	@get:Rule
	val tmp = TemporaryFolder()

	private lateinit var service: GradleBuildService

	@Before
	fun setUp() {
		// Taking the slot mkdirs this first.
		Environment.TMP_DIR = tmp.newFolder("tmp")
		// Attached but not created: onCreate posts the foreground notification and registers the
		// service in Lookup, and the slot depends on neither.
		service = Robolectric.buildService(GradleBuildService::class.java).get()
	}

	@Test
	fun `a server that dies mid-build releases the slot and ends the build`() {
		val rpc = CompletableFuture<TaskExecutionResult>()
		val server = mockk<IToolingApiServer> { every { executeTasks(any()) } returns rpc }
		service.onListenerStarted(server, ByteArrayInputStream(ByteArray(0)))

		val build = service.executeTasks(listOf(":app:assembleDebug"))
		// The slot is taken on the build's own future chain, not on the caller's thread.
		awaitUntil { service.isBuildInProgress }
		assertThat(service.isBuildInProgress).isTrue()

		service.onServerExited(137)

		// The slot is released on that same chain, just before the caller's future completes.
		awaitUntil { build.isDone }
		assertThat(service.isBuildInProgress).isFalse()
		assertThat(build.isDone).isTrue()
		val failure = runCatching { rpc.join() }.exceptionOrNull()
		assertThat(failure).isInstanceOf(CompletionException::class.java)
		assertThat(failure).hasCauseThat().isInstanceOf(ToolingServerNotStartedException::class.java)
	}

	@Test
	fun `a server exit with no build in flight leaves the slot free`() {
		val server = mockk<IToolingApiServer>()
		service.onListenerStarted(server, ByteArrayInputStream(ByteArray(0)))

		service.onServerExited(137)

		assertThat(service.isBuildInProgress).isFalse()
		assertThat(service.isToolingServerStarted()).isFalse()
	}

	/** Polls [condition] for up to [timeoutMs]; the assertion that follows reports the outcome. */
	private fun awaitUntil(
		timeoutMs: Long = 5_000,
		condition: () -> Boolean,
	) {
		val deadline = System.currentTimeMillis() + timeoutMs
		while (!condition() && System.currentTimeMillis() < deadline) {
			Thread.sleep(10)
		}
	}
}
