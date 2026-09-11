package com.itsaky.androidide.tooling.impl

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.tooling.api.IToolingApiClient
import com.itsaky.androidide.tooling.api.messages.BuildId
import com.itsaky.androidide.tooling.api.messages.InitializeProjectParams
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
import io.mockk.slot
import io.mockk.spyk
import io.mockk.verify
import org.gradle.tooling.BuildCancelledException
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.ProjectConnection
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.File
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
	) = InitializeProjectParams(
		// Required since ADFA-2784 added it, and never supplied here: this file has not compiled
		// on stage since, and no workflow runs :subprojects: tests, so nothing said so.
		buildId = BuildId.Unknown,
		directory = directory,
		needsGradleSync = forceSync,
	)

	private data class MockServer(
		val server: ToolingApiServerImpl,
		val connector: GradleConnector,
		val connection: ProjectConnection,
	)

	private fun mockkToolingServer(): MockServer {
		val server = spyk(ToolingApiServerImpl())
		val connector = mockk<GradleConnector>(relaxed = true)
		val connection = mockk<ProjectConnection>(relaxed = true)

		// ensure that we do not start actual Gradle build
		every {
			server.getOrConnectProject(
				projectDir = any(),
				forceConnect = true,
				initParams = any(),
				gradleDist = any(),
			)
		} returns (connector to connection)

		return MockServer(server, connector, connection)
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
