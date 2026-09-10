package com.itsaky.androidide.tooling.impl

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.tooling.api.messages.BuildId
import com.itsaky.androidide.tooling.api.messages.GradleDistributionParams
import com.itsaky.androidide.tooling.api.messages.InitializeProjectParams
import com.itsaky.androidide.tooling.api.messages.TaskExecutionMessage
import com.itsaky.androidide.tooling.api.messages.result.InitializeResult
import com.itsaky.androidide.tooling.api.messages.result.TaskExecutionResult
import com.itsaky.androidide.tooling.api.messages.result.isSuccessful
import com.itsaky.androidide.tooling.api.sync.ProjectSyncHelper
import com.itsaky.androidide.tooling.impl.sync.RootModelBuilder
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkAll
import io.mockk.verify
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.ProjectConnection
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

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
		directory = directory,
		gradleDistribution = gradleDistribution,
		needsGradleSync = forceSync,
		buildId = BuildId.Unknown,
	)

	@After
	fun tearDown() {
		unmockkAll()
	}

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
	fun `GIVEN a connection failure THEN classifying it changes nothing`() {
		val server = ToolingApiServerImpl()
		val connector = mockk<GradleConnector>(relaxed = true)
		every { connector.forProjectDirectory(any()) } returns connector
		every { connector.connect() } returns mockk(relaxed = true)

		mockkStatic(GradleConnector::class)
		every { GradleConnector.newConnector() } returns connector

		server.getOrConnectProject(File("/does/not/exist"), forceConnect = true)

		// Classification is pure. It briefly marked the connection suspect, and initialize's catch
		// routes every sync failure through it -- so a model builder throwing an
		// IllegalStateException condemned a connection opened moments earlier, and the next build's
		// reconnect sent the warm daemon StopWhenIdle. ADFA-5589's own bug, on a new trigger.
		assertThat(server.getTaskFailureType(IllegalStateException("connection closed")))
			.isEqualTo(TaskExecutionResult.Failure.CONNECTION_CLOSED)
		assertThat(server.connectionSuspect).isFalse()
		assertThat(server.isConnected).isTrue()
	}

	@Test
	fun `GIVEN a build failed on a dead connection THEN the next build reconnects rather than reusing it`() {
		val server = ToolingApiServerImpl()
		val connector = mockk<GradleConnector>(relaxed = true)
		every { connector.forProjectDirectory(any()) } returns connector
		val first = mockk<ProjectConnection>(relaxed = true)
		val second = mockk<ProjectConnection>(relaxed = true)
		every { connector.connect() } returns first andThen second

		mockkStatic(GradleConnector::class)
		every { GradleConnector.newConnector() } returns connector

		server.getOrConnectProject(File("/does/not/exist"), forceConnect = true, initParams = testInitParams())
		// Set directly: only doInitialize writes it, and that needs a real project directory.
		// connectionForBuild reconnects to the project the last initialize named.
		server.lastInitParams = testInitParams()
		assertThat(server.connectionForBuild()).isSameInstanceAs(first)

		// What the flag is for: the connection stays in place and the *next build* replaces it,
		// which is what reaches connector.disconnect() and releases the old daemon client and its
		// threads. Nulling the fields at the point of failure skipped that and stranded them.
		server.connectionSuspect = true

		assertThat(server.connectionForBuild()).isSameInstanceAs(second)
		assertThat(server.connectionSuspect).isFalse()
		verify(atLeast = 1) { connector.disconnect() }
	}

	@Test
	fun `GIVEN a reconnect that throws THEN the build reports a failure rather than completing exceptionally`() {
		val (server) = mockkToolingServer()
		every { server.isServerInitialized() } returns CompletableFuture.completedFuture(true)
		every { server.connectionForBuild() } throws IllegalStateException("cannot reconnect")

		mockkObject(Main)
		every { Main.checkGradleWrapper() } returns Unit

		// Everything before the Gradle call used to sit outside the try, so a throw here escaped as
		// an ExecutionException where the client expected a classified failure -- and the same line
		// did it twice, first as a checkNotNull and then as the reconnect that replaced it.
		val result =
			server
				.executeTasks(TaskExecutionMessage(tasks = listOf("assembleDebug"), buildId = BuildId.Unknown))
				.get(5, TimeUnit.SECONDS)

		assertThat(result.isSuccessful).isFalse()
		assertThat(result.failure).isEqualTo(TaskExecutionResult.Failure.CONNECTION_CLOSED)
	}

	@Test
	fun `GIVEN a failure before the build runs THEN the connection is not condemned for it`() {
		val (server) = mockkToolingServer()
		every { server.isServerInitialized() } returns CompletableFuture.completedFuture(true)

		mockkObject(Main)
		// A setup failure, not a build one. classifyTaskFailure reads any IllegalStateException as
		// CONNECTION_CLOSED, so marking the connection from runBuild's whole-action catch condemned
		// it over a throw from here -- and the next build's reconnect sends the running daemon
		// StopWhenIdle, which is the cold start this ticket exists to prevent.
		every { Main.checkGradleWrapper() } throws IllegalStateException("no wrapper")

		val result =
			server
				.executeTasks(TaskExecutionMessage(tasks = listOf("assembleDebug"), buildId = BuildId.Unknown))
				.get(5, TimeUnit.SECONDS)

		assertThat(result.isSuccessful).isFalse()
		assertThat(server.connectionSuspect).isFalse()
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
}
