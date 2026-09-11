package com.itsaky.androidide.services.builder

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.managers.ToolsManager
import com.itsaky.androidide.tooling.api.IToolingApiClient
import com.itsaky.androidide.tooling.api.IToolingApiServer
import com.itsaky.androidide.tooling.api.messages.ClientGradleBuildConfig
import com.itsaky.androidide.tooling.api.messages.LogMessageParams
import com.itsaky.androidide.tooling.api.messages.result.BuildInfo
import com.itsaky.androidide.tooling.api.messages.result.BuildResult
import com.itsaky.androidide.tooling.api.messages.result.GradleWrapperCheckResult
import com.itsaky.androidide.tooling.events.ProgressEvent
import com.itsaky.androidide.utils.Environment
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The runner's exit path: once the server process is gone, nothing may still read it as started.
 *
 * The defect this pins: after `waitFor()` returned, the runner kept [ToolingServerRunner.isStarted]
 * and [ToolingServerRunner.pid], and [ToolingServerRunner.Observer.onServerExited] was declared but
 * never called. The service then reused the dead runner on every later bind and sent builds to a
 * proxy whose process no longer existed.
 */
class ToolingServerRunnerTest {
	/**
	 * A server process the test ends on demand. `pid` is the field the runner reads reflectively;
	 * the server's stdout stays open until [exit] so the launcher keeps listening until then.
	 */
	private class FakeProcess(
		private val exitCode: Int,
	) : Process() {
		@Suppress("unused")
		private val pid: Int = 4242
		private val exited = CountDownLatch(1)
		private val serverStdout = PipedOutputStream()
		private val serverStdoutRead = PipedInputStream(serverStdout)

		override fun getInputStream(): InputStream = serverStdoutRead

		override fun getOutputStream(): OutputStream = ByteArrayOutputStream()

		override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

		override fun waitFor(): Int {
			exited.await()
			return exitCode
		}

		override fun exitValue(): Int {
			check(exited.count == 0L) { "process has not exited" }
			return exitCode
		}

		override fun destroy() {
			exit()
		}

		override fun destroyForcibly(): Process {
			exit()
			return this
		}

		fun exit() {
			serverStdout.close()
			exited.countDown()
		}
	}

	/**
	 * @property awaitExitInListenerStarted holds the listener-started callback, which the runner
	 *   calls just before it marks itself started, until an exit has been reported or a second has
	 *   passed. A reset that runs on the process-watching job then lands before the started mark
	 *   instead of racing it, so the fast-exit test fails for that placement every time rather
	 *   than by timing.
	 */
	private class RecordingObserver(
		private val awaitExitInListenerStarted: Boolean = false,
	) : ToolingServerRunner.Observer {
		val exitCodes = mutableListOf<Int>()
		private val exited = CountDownLatch(1)

		override fun onListenerStarted(
			server: IToolingApiServer,
			errorStream: InputStream,
		) {
			if (awaitExitInListenerStarted) {
				exited.await(1, TimeUnit.SECONDS)
			}
		}

		override fun onServerExited(exitCode: Int) {
			exitCodes += exitCode
			exited.countDown()
		}

		override fun getClient(): IToolingApiClient = NoOpClient
	}

	/**
	 * A hand-written client rather than a mock: the launcher scans the client's class for RPC
	 * annotations, and a mock's overrides carry them a second time ("Duplicate RPC method").
	 */
	private object NoOpClient : IToolingApiClient {
		override fun logMessage(params: LogMessageParams) = Unit

		override fun logOutput(line: String) = Unit

		override fun prepareBuild(buildInfo: BuildInfo): CompletableFuture<ClientGradleBuildConfig> = CompletableFuture()

		override fun onBuildSuccessful(result: BuildResult) = Unit

		override fun onBuildFailed(result: BuildResult) = Unit

		override fun onGradleDaemonStarted(pid: Int) = Unit

		override fun onGradleDaemonExited(pid: Int) = Unit

		override fun onProgressEvent(event: ProgressEvent) = Unit

		override fun checkGradleWrapperAvailability(): CompletableFuture<GradleWrapperCheckResult> = CompletableFuture()
	}

	private val started = CountDownLatch(1)
	private var runner: ToolingServerRunner? = null

	@Before
	fun setUp() {
		// Only read while the command line is assembled; the fake process never runs it.
		Environment.JAVA = File("java")
		Environment.TOOLING_API_JAR = File("tooling-api.jar")
		mockkStatic(ToolsManager::class)
		every { ToolsManager.ensureToolingJar(any()) } returns true
	}

	@After
	fun tearDown() {
		runner?.release()
		unmockkStatic(ToolsManager::class)
	}

	private fun runner(
		process: Process,
		observer: RecordingObserver,
	): ToolingServerRunner =
		ToolingServerRunner(
			listener = ToolingServerRunner.OnServerStartListener { started.countDown() },
			observer = observer,
			context = mockk<Context>(relaxed = true),
			startProcess = { _, _ -> process },
		).also { runner = it }

	@Test
	fun `a server that exits is no longer started, and its exit code reaches the observer`() {
		val process = FakeProcess(exitCode = 137)
		val observer = RecordingObserver()
		val runner = runner(process, observer)

		val job = runner.startAsync(emptyMap())
		assertThat(started.await(10, TimeUnit.SECONDS)).isTrue()
		assertThat(runner.isStarted).isTrue()
		assertThat(runner.pid).isEqualTo(4242)

		process.exit()
		runBlocking { withTimeout(10_000) { job.join() } }

		assertThat(runner.isStarted).isFalse()
		assertThat(runner.pid).isNull()
		assertThat(observer.exitCodes).containsExactly(137)
	}

	@Test
	fun `a server that dies before the listener is wired never reads as started`() {
		// java -jar on a missing jar exits before the launcher is even built. A reset inside the
		// process-watching job loses that race with isStarted being set, so the reset has to run
		// after both jobs have ended.
		val process = FakeProcess(exitCode = 1).apply { exit() }
		val observer = RecordingObserver(awaitExitInListenerStarted = true)
		val runner = runner(process, observer)

		runBlocking { withTimeout(10_000) { runner.startAsync(emptyMap()).join() } }

		assertThat(runner.isStarted).isFalse()
		assertThat(runner.pid).isNull()
		assertThat(observer.exitCodes).containsExactly(1)
	}
}
