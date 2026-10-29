package com.itsaky.androidide.tooling.impl

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.tooling.api.IToolingApiClient
import com.itsaky.androidide.tooling.api.messages.BuildId
import com.itsaky.androidide.tooling.api.messages.GradleDistributionParams
import com.itsaky.androidide.tooling.api.messages.InitializeProjectParams
import com.itsaky.androidide.tooling.api.messages.TaskExecutionMessage
import com.itsaky.androidide.tooling.api.messages.result.BuildCancellationRequestResult
import com.itsaky.androidide.tooling.api.messages.result.BuildResult
import com.itsaky.androidide.tooling.api.messages.result.InitializeResult
import com.itsaky.androidide.tooling.api.messages.result.TaskExecutionResult
import com.itsaky.androidide.tooling.api.messages.result.isSuccessful
import com.itsaky.androidide.tooling.api.sync.ProjectSyncHelper
import com.itsaky.androidide.tooling.impl.sync.RootModelBuilder
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.spyk
import io.mockk.unmockkAll
import io.mockk.verify
import org.gradle.tooling.BuildCancelledException
import org.gradle.tooling.BuildLauncher
import org.gradle.tooling.GradleConnectionException
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.ProjectConnection
import org.gradle.tooling.events.OperationType
import org.gradle.tooling.events.ProgressListener
import org.junit.After
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * @author Akash Yadav
 */
@RunWith(JUnit4::class)
class ToolingApiServerImplTest {
	private fun testInitParams(
		directory: String = "/does/not/exist",
		forceSync: Boolean = false,
		gradleDistribution: GradleDistributionParams = GradleDistributionParams.WRAPPER,
	) = InitializeProjectParams(
		// Required since ADFA-2784 added it, and never supplied here: this file has not compiled
		// on stage since, and no workflow runs :subprojects: tests, so nothing said so.
		buildId = BuildId.Unknown,
		directory = directory,
		gradleDistribution = gradleDistribution,
		needsGradleSync = forceSync,
	)

	private data class MockServer(
		val server: ToolingApiServerImpl,
		val connector: GradleConnector,
		val connection: ProjectConnection,
	)

	@After
	fun tearDown() {
		// These tests mock singletons. Left in place they follow the JVM into the next test class.
		unmockkAll()
	}

	private fun mockkToolingServer(): MockServer {
		val server = spyk(ToolingApiServerImpl())
		val connector = mockk<GradleConnector>(relaxed = true)
		val connection = mockk<ProjectConnection>(relaxed = true)

		// ensure that we do not start actual Gradle build
		every {
			server.getOrConnectProject(
				projectDir = any(),
				forceConnect = any(),
				initParams = any(),
				gradleDist = any(),
			)
		} returns (connector to connection)

		return MockServer(server, connector, connection)
	}

	@Test
	fun `GIVEN the same project twice THEN the connector can be reused`() {
		// Two separately built objects describing the same connection, which is what the client
		// sends: every initialize request arrives freshly deserialized from JSON-RPC. The guard used
		// to be `params == lastInitParams`, which is reference equality on this type and so answered
		// false for exactly this case (ADFA-5589).
		val first = testInitParams()
		val second = testInitParams()

		assertThat(first).isNotSameInstanceAs(second)
		assertThat(ToolingApiServerImpl.describesSameConnection(first, second)).isTrue()
	}

	@Test
	fun `GIVEN the same directory spelled differently THEN the connector can be reused`() {
		// forProjectDirectory takes a File, so a trailing separator is the same connector.
		assertThat(
			ToolingApiServerImpl.describesSameConnection(
				testInitParams(directory = "/does/not/exist"),
				testInitParams(directory = "/does/not/exist/"),
			),
		).isTrue()
	}

	@Test
	fun `GIVEN a different project directory THEN the connector cannot be reused`() {
		// A connector is bound to its project directory, so reusing one across projects would run
		// the next build against the previous project's connection.
		assertThat(
			ToolingApiServerImpl.describesSameConnection(
				testInitParams(directory = "/does/not/exist"),
				testInitParams(directory = "/somewhere/else"),
			),
		).isFalse()
	}

	@Test
	fun `GIVEN a different Gradle distribution THEN the connector cannot be reused`() {
		// The other thing a connector is bound to. forInstallationDir is what the app actually sends
		// for the gradleInstallationDir preference, so that is the case worth pinning: reusing a
		// connector here would silently build with the distribution the user just changed away from.
		val wrapper = testInitParams()
		val installation =
			testInitParams(gradleDistribution = GradleDistributionParams.forInstallationDir("/opt/gradle"))

		assertThat(ToolingApiServerImpl.describesSameConnection(wrapper, installation)).isFalse()
	}

	@Test
	fun `GIVEN nothing initialized yet THEN the connector cannot be reused`() {
		assertThat(ToolingApiServerImpl.describesSameConnection(null, testInitParams())).isFalse()
	}

	@Test
	fun `GIVEN the wrapper names a new distribution THEN the connector cannot be reused`() {
		// Two WRAPPER params compare equal across a wrapper upgrade: the distribution is resolved
		// from gradle-wrapper.properties inside connect() and frozen into the connection, so reusing
		// it would keep building with the Gradle the user just upgraded away from.
		assertThat(
			ToolingApiServerImpl.wrapperStillMatches(
				testInitParams(),
				recorded = "https://example.invalid/gradle-8.14.3-bin.zip",
				current = "https://example.invalid/gradle-9.0-bin.zip",
			),
		).isFalse()
	}

	@Test
	fun `GIVEN the wrapper is unchanged THEN the connector can be reused`() {
		val url = "https://example.invalid/gradle-8.14.3-bin.zip"
		assertThat(
			ToolingApiServerImpl.wrapperStillMatches(testInitParams(), recorded = url, current = url),
		).isTrue()
	}

	@Test
	fun `GIVEN a non-wrapper distribution THEN the wrapper properties do not matter`() {
		// An installation-dir connection does not read gradle-wrapper.properties at all.
		assertThat(
			ToolingApiServerImpl.wrapperStillMatches(
				testInitParams(gradleDistribution = GradleDistributionParams.forInstallationDir("/opt/gradle")),
				recorded = null,
				current = "https://example.invalid/gradle-9.0-bin.zip",
			),
		).isTrue()
	}

	@Test
	fun `GIVEN a wrapper properties file THEN its distribution URL is read`() {
		val project = Files.createTempDirectory("adfa5589").toFile()
		val properties = File(project, "gradle/wrapper/gradle-wrapper.properties")
		properties.parentFile.mkdirs()
		properties.writeText("distributionUrl=https\\://example.invalid/gradle-8.14.3-bin.zip\n")

		assertThat(ToolingApiServerImpl.wrapperDistributionUrl(project.path))
			.isEqualTo("https://example.invalid/gradle-8.14.3-bin.zip")
		assertThat(ToolingApiServerImpl.wrapperDistributionUrl("/does/not/exist")).isNull()

		project.deleteRecursively()
	}

	@Test
	fun `GIVEN the connector can be reused WHEN initializing THEN do not force a reconnect`() {
		val (server) = mockkReusableServer(canReuse = true)

		server.initialize(testInitParams()).get(5, TimeUnit.SECONDS)

		// forceConnect is the whole point of the reuse check: it is what reaches
		// connector.disconnect(), and that is what sends the running daemon StopWhenIdle. Asserting
		// describesSameConnection alone would leave this wiring free to invert unnoticed.
		verify(exactly = 1) {
			server.getOrConnectProject(
				projectDir = any(),
				forceConnect = false,
				initParams = any(),
				gradleDist = any(),
			)
		}
	}

	@Test
	fun `GIVEN the connector cannot be reused WHEN initializing THEN force a reconnect`() {
		val (server) = mockkReusableServer(canReuse = false)

		server.initialize(testInitParams()).get(5, TimeUnit.SECONDS)

		verify(exactly = 1) {
			server.getOrConnectProject(
				projectDir = any(),
				forceConnect = true,
				initParams = any(),
				gradleDist = any(),
			)
		}
	}

	@Test
	fun `GIVEN a connect that fails THEN the dead connection is not left behind for reuse`() {
		val server = ToolingApiServerImpl()
		val connector = mockk<GradleConnector>(relaxed = true)
		every { connector.forProjectDirectory(any()) } returns connector
		every { connector.connect() } returns
			mockk(relaxed = true) andThenThrows RuntimeException("connect failed")

		mockkStatic(GradleConnector::class)
		every { GradleConnector.newConnector() } returns connector

		server.getOrConnectProject(File("/does/not/exist"), forceConnect = true)
		assertThat(server.isConnected).isTrue()

		// The reconnect disconnects the open connector before it opens the new one, so a connect
		// that throws must not leave that dead pair in place: the next initialize whose params match
		// would reuse it and fail every build with CONNECTION_CLOSED until the server restarts.
		val reconnect = runCatching { server.getOrConnectProject(File("/does/not/exist"), forceConnect = true) }

		assertThat(reconnect.isFailure).isTrue()
		assertThat(server.isConnected).isFalse()
	}

	/** A server whose connect, project directory check and Gradle sync are all stubbed out. */
	private fun mockkReusableServer(canReuse: Boolean): MockServer {
		mockkObject(RootModelBuilder)
		every {
			RootModelBuilder.build(any(), any())
		} returns ProjectSyncHelper.cacheFileForProject(File("/does/not/exist"))

		val mocks = mockkToolingServer()
		every { mocks.server.validateProjectDirectory(any()) } returns null
		every { mocks.server.canReuseConnector(any()) } returns canReuse
		return mocks
	}

	@Test
	fun `GIVEN any initialization params WHEN project init fails THEN report as failure`() {
		mockkObject(RootModelBuilder)
		every {
			// Simulate a Gradle sync failure
			RootModelBuilder.build(
				any(),
				any(),
			)
		} throws RuntimeException("intentional failure")

		val (server) = mockkToolingServer()

		every {
			// ensure we don't fail on non-existent project directory
			server.validateProjectDirectory(any())
		} returns null

		val result = server.initialize(testInitParams()).get(5, TimeUnit.SECONDS)
		assertThat(result).isNotNull()
		assertThat(result.isSuccessful).isFalse()
		assertThat(result).isInstanceOf(InitializeResult.Failure::class.java)

		// unknown error because of the mocked runtime exception
		assertThat((result as InitializeResult.Failure).failure).isEqualTo(TaskExecutionResult.Failure.UNKNOWN)
	}

	@Test
	fun `GIVEN a build the user stopped WHEN it fails THEN the client is told it was a cancel`() {
		mockkObject(RootModelBuilder)
		every {
			// Gradle raises this, and only this, for a build that was cancelled.
			RootModelBuilder.build(any(), any())
		} throws BuildCancelledException("stopped by the user")

		val (server) = mockkToolingServer()

		every {
			server.validateProjectDirectory(any())
		} returns null

		val client = mockk<IToolingApiClient>(relaxed = true)
		server.connect(client)

		val result = server.initialize(testInitParams()).get(5, TimeUnit.SECONDS)
		assertThat((result as InitializeResult.Failure).failure)
			.isEqualTo(TaskExecutionResult.Failure.BUILD_CANCELLED)

		// The same verdict has to reach the client, not only the caller of initialize. It did not,
		// and the editor was left reconstructing "was that a cancel?" from the order its own
		// callbacks happened to arrive in -- which it got wrong, annotating a build the user had
		// stopped as a failure (ADFA-5542).
		//
		// This drives the sync path. The task-run path is the one the ticket is really about, and
		// standing it up needs a live ProjectConnection; instead of testing the two separately,
		// notifyBuildFailure now classifies and notifies in one call and hands the answer back, so
		// neither site can report a failure without saying which, or tell the client one thing and
		// its caller another. There is one place left to get this wrong and this covers it.
		val reported = slot<BuildResult>()
		verify { client.onBuildFailed(capture(reported)) }
		assertThat(reported.captured.failure).isEqualTo(TaskExecutionResult.Failure.BUILD_CANCELLED)
	}

	@Test
	fun `GIVEN a sync that finished WHEN a Stop arrives THEN there is no build to cancel`() {
		mockkObject(RootModelBuilder)
		every { RootModelBuilder.build(any(), any()) } returns File("/does/not/exist/cache")

		val (server) = mockkToolingServer()
		every { server.validateProjectDirectory(any()) } returns null
		server.connect(mockk<IToolingApiClient>(relaxed = true))

		server.initialize(testInitParams()).get(5, TimeUnit.SECONDS)

		// The token for the sync's own build was never cleared on any outcome, so it outlived the
		// build it belonged to. A Stop pressed afterwards cancelled that dead source and answered
		// "enqueued" -- telling the user a build was being stopped when none was running, and, once
		// a real build had started, leaving it running while claiming otherwise.
		val result = server.cancelCurrentBuild().get(5, TimeUnit.SECONDS)

		assertThat(result.wasEnqueued).isFalse()
		assertThat(result.failureReason).isEqualTo(BuildCancellationRequestResult.Reason.NO_RUNNING_BUILD)
	}

	@Test
	fun `GIVEN a sync that failed WHEN a Stop arrives THEN there is no build to cancel`() {
		mockkObject(RootModelBuilder)
		every { RootModelBuilder.build(any(), any()) } throws RuntimeException("intentional failure")

		val (server) = mockkToolingServer()
		every { server.validateProjectDirectory(any()) } returns null
		server.connect(mockk<IToolingApiClient>(relaxed = true))

		server.initialize(testInitParams()).get(5, TimeUnit.SECONDS)

		// The failing path leaked it the same way the succeeding one did.
		val result = server.cancelCurrentBuild().get(5, TimeUnit.SECONDS)

		assertThat(result.wasEnqueued).isFalse()
	}

	@Test
	fun `GIVEN force sync not requested WHEN sync files are unreadable THEN sync anyway`() {
		val initParams = testInitParams(forceSync = false)
		val cacheFile = ProjectSyncHelper.cacheFileForProject(File(initParams.directory))

		mockkObject(RootModelBuilder)
		every {
			// simulate a successful cache write
			RootModelBuilder.build(
				any(),
				any(),
			)
		} returns cacheFile

		mockkObject(ProjectSyncHelper)
		every {
			// simulate unreadable cache files
			ProjectSyncHelper.areSyncFilesReadable(any(), any())
		} returns false

		val (server) = mockkToolingServer()

		every {
			// ensure we don't fail on non-existent project directory
			server.validateProjectDirectory(any())
		} returns null

		val result = server.initialize(initParams).get(5, TimeUnit.SECONDS)
		assertThat(result).isNotNull()
		assertThat(result.isSuccessful).isTrue()
		assertThat(result).isInstanceOf(InitializeResult.Success::class.java)
		assertThat((result as InitializeResult.Success).cacheFile).isEqualTo(cacheFile)

		verify(exactly = 1) {
			// ensure gradle sync was requested
			RootModelBuilder.build(initParams, any())
		}
	}

	@Test
	fun `GIVEN the build throws a GradleConnectionException THEN the connection is kept and not disconnected`() {
		// A ProjectConnection is stopped only by our own close() or disconnect(). It does not die
		// with the daemon, so the next run() on it starts a fresh one. Reconnecting after a failed
		// build would disconnect() -- which sends the running daemon StopWhenIdle -- and cost the
		// cold start ADFA-5589 exists to prevent.
		val server = spyk(ToolingApiServerImpl())
		val connector = mockk<GradleConnector>(relaxed = true)
		val connection = mockk<ProjectConnection>(relaxed = true)
		val builder = mockk<BuildLauncher>(relaxed = true)
		every { connector.forProjectDirectory(any()) } returns connector
		every { connector.connect() } returns connection
		every { connection.newBuild() } returns builder
		// A relaxed mock cannot return itself from these generic, self-typed setters.
		every { builder.setStandardInput(any<InputStream>()) } returns builder
		every { builder.setStandardOutput(any<OutputStream>()) } returns builder
		every { builder.setStandardError(any<OutputStream>()) } returns builder
		every { builder.addProgressListener(any<ProgressListener>(), any<Set<OperationType>>()) } returns builder
		every { builder.addArguments(any<List<String>>()) } returns builder
		every { builder.addJvmArguments(any<List<String>>()) } returns builder
		every { builder.withCancellationToken(any()) } returns builder
		every { builder.run() } throws GradleConnectionException("daemon disappeared", RuntimeException())

		mockkStatic(GradleConnector::class)
		every { GradleConnector.newConnector() } returns connector

		mockkObject(Main)
		every { Main.checkGradleWrapper() } returns Unit
		every { server.isServerInitialized() } returns CompletableFuture.completedFuture(true)

		server.getOrConnectProject(File("/does/not/exist"), forceConnect = true)

		val message = TaskExecutionMessage(tasks = listOf("assembleDebug"), buildId = BuildId.Unknown)
		repeat(2) {
			val result = server.executeTasks(message).get(5, TimeUnit.SECONDS)
			assertThat(result.isSuccessful).isFalse()
			assertThat(result.failure).isEqualTo(TaskExecutionResult.Failure.CONNECTION_ERROR)
		}

		assertThat(server.isConnected).isTrue()
		// Both builds ran on the one connection, which was opened once.
		verify(exactly = 1) { connector.connect() }
		verify(exactly = 2) { connection.newBuild() }
		verify(exactly = 0) { connector.disconnect() }
		verify(exactly = 0) { connection.close() }
	}

	@Test
	fun `GIVEN no open connection THEN the build reports a failure rather than completing exceptionally`() {
		// mockkToolingServer stubs getOrConnectProject, so nothing was connected.
		val (server) = mockkToolingServer()
		every { server.isServerInitialized() } returns CompletableFuture.completedFuture(true)

		mockkObject(Main)
		every { Main.checkGradleWrapper() } returns Unit

		// Everything before the Gradle call used to sit outside the try, so a throw here escaped as
		// an ExecutionException where the client expected a classified failure.
		val result =
			server
				.executeTasks(TaskExecutionMessage(tasks = listOf("assembleDebug"), buildId = BuildId.Unknown))
				.get(5, TimeUnit.SECONDS)

		assertThat(result.isSuccessful).isFalse()
		assertThat(result.failure).isEqualTo(TaskExecutionResult.Failure.CONNECTION_CLOSED)
	}

	@Test
	fun `GIVEN a build already running THEN a second is refused rather than sharing its connection`() {
		// The refusal, not the atomicity. This passes against a read-then-write too -- the first
		// build has set the flag long before the second reads it -- so it pins the behaviour and
		// not the CAS. A genuine interleaving is not reproducible on demand, so the compareAndSet
		// in runBuild is argued from the threading model rather than pinned here.
		val (server) = mockkToolingServer()
		every { server.isServerInitialized() } returns CompletableFuture.completedFuture(true)

		val started = java.util.concurrent.CountDownLatch(1)
		val release = java.util.concurrent.CountDownLatch(1)
		mockkObject(Main)
		every { Main.checkGradleWrapper() } answers {
			started.countDown()
			release.await(5, TimeUnit.SECONDS)
			throw IllegalStateException("done")
		}

		val first = server.executeTasks(TaskExecutionMessage(tasks = listOf("a"), buildId = BuildId.Unknown))
		assertThat(started.await(5, TimeUnit.SECONDS)).isTrue()

		val second = server.executeTasks(TaskExecutionMessage(tasks = listOf("b"), buildId = BuildId.Unknown))
		val refused = runCatching { second.get(5, TimeUnit.SECONDS) }.exceptionOrNull()

		release.countDown()
		first.get(5, TimeUnit.SECONDS)

		assertThat(refused).isNotNull()
		assertThat(refused).hasCauseThat().hasMessageThat().contains("already in progress")
	}

	@Test
	fun `GIVEN readable sync files WHEN the stored schema version is stale THEN sync anyway`() {
		val initParams = testInitParams(forceSync = false)
		val cacheFile = ProjectSyncHelper.cacheFileForProject(File(initParams.directory))

		mockkObject(RootModelBuilder)
		every { RootModelBuilder.build(any(), any()) } returns cacheFile

		mockkObject(ProjectSyncHelper)

		// The overload the server actually calls, so the stub does not ride on internal delegation.
		every { ProjectSyncHelper.areSyncFilesReadable(any<File>()) } returns true
		every { ProjectSyncHelper.areSyncFilesReadable(any(), any()) } returns true

		// The cache is present and readable, and only the stored schema version rules it out.
		every { ProjectSyncHelper.isSyncMetaVersionCurrent(any()) } returns false

		val (server) = mockkToolingServer()
		every { server.validateProjectDirectory(any()) } returns null

		val result = server.initialize(initParams).get(5, TimeUnit.SECONDS)
		assertThat(result).isInstanceOf(InitializeResult.Success::class.java)
		assertThat((result as InitializeResult.Success).cacheFile).isEqualTo(cacheFile)

		verify(exactly = 1) { RootModelBuilder.build(initParams, any()) }
	}

	@Test
	fun `shutting the server down stops the daemon watcher`() {
		// The defect this PR exists to fix, pinned at the caller. The watcher's own shutdown() was
		// already correct on stage -- what was missing was anything calling it, so a test of
		// GradleDaemonWatcher.shutdown() in isolation passes against the unfixed server and pins
		// nothing. Deleting the block in ToolingApiServerImpl.shutdown() has to fail a test.
		val watcher = mockk<GradleDaemonWatcher>(relaxed = true)
		val server = ToolingApiServerImpl(newDaemonWatcher = { _, _ -> watcher })

		// A build, to bring the watcher into being the way a session does: runBuild calls
		// onBuildStarted, which is what initializes the lazy.
		server.initialize(testInitParams()).get(5, TimeUnit.SECONDS)
		server.shutdown().get(5, TimeUnit.SECONDS)

		verify(exactly = 1) { watcher.shutdown() }
	}

	@Test
	fun `a server that never ran a build does not build a watcher just to stop it`() {
		// Through the lazy delegate, not the property: touching the property would construct a
		// watcher, and its scheduler thread, only to shut it down again.
		var built = 0
		val server =
			ToolingApiServerImpl(
				newDaemonWatcher = { _, _ ->
					built++
					mockk(relaxed = true)
				},
			)

		server.shutdown().get(5, TimeUnit.SECONDS)

		assertThat(built).isEqualTo(0)
	}

	@Test
	fun `a build that starts after shutdown does not build a watcher`() {
		// Half of the shutdown-versus-build race. A build request already in flight runs its body on
		// the common pool, so it can reach startDaemonWatch after shutdown has looked for a watcher
		// and found none. Building one here leaves a scheduler nothing will ever stop.
		var built = 0
		val server =
			ToolingApiServerImpl(
				newDaemonWatcher = { _, _ ->
					built++
					mockk(relaxed = true)
				},
			)

		server.shutdown().get(5, TimeUnit.SECONDS)
		server.initialize(testInitParams()).get(5, TimeUnit.SECONDS)

		assertThat(built).isEqualTo(0)
	}

	@Test
	fun `shutdown waits for a watcher a concurrent build is building`() {
		// The other half: the build gets there first, and is still inside the constructor when
		// shutdown asks. `lazy.isInitialized()` reads false throughout that window, so without the
		// lock shutdown concludes there is no watcher and returns while one is being built.
		//
		// The negative assertion is bounded rather than exact: it proves shutdown did not conclude
		// within a second, against an unlocked shutdown that runs in milliseconds.
		val watcher = mockk<GradleDaemonWatcher>(relaxed = true)
		val constructing = CountDownLatch(1)
		val release = CountDownLatch(1)
		val server =
			ToolingApiServerImpl(
				newDaemonWatcher = { _, _ ->
					constructing.countDown()
					assertThat(release.await(5, TimeUnit.SECONDS)).isTrue()
					watcher
				},
			)

		val build = server.initialize(testInitParams())
		assertThat(constructing.await(5, TimeUnit.SECONDS)).isTrue()

		val shutdown = server.shutdown()
		try {
			shutdown.get(1, TimeUnit.SECONDS)
			fail("shutdown completed while the watcher was still being constructed")
		} catch (_: TimeoutException) {
			// expected: shutdown is waiting on the lock the build holds
		}

		release.countDown()
		build.get(5, TimeUnit.SECONDS)
		shutdown.get(5, TimeUnit.SECONDS)

		verify(exactly = 1) { watcher.shutdown() }
	}
}
