package com.itsaky.androidide.tooling.impl

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.tooling.api.IToolingApiClient
import com.itsaky.androidide.tooling.api.messages.BuildId
import com.itsaky.androidide.tooling.api.messages.GradleDistributionParams
import com.itsaky.androidide.tooling.api.messages.InitializeProjectParams
import com.itsaky.androidide.tooling.api.messages.result.InitializeResult
import com.itsaky.androidide.tooling.api.messages.result.TaskExecutionResult
import com.itsaky.androidide.tooling.api.messages.result.isSuccessful
import com.itsaky.androidide.tooling.api.sync.ProjectSyncHelper
import com.itsaky.androidide.tooling.impl.sync.RootModelBuilder
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.spyk
import io.mockk.verify
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.ProjectConnection
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * @author Akash Yadav
 */
@RunWith(JUnit4::class)
class ToolingApiServerImplTest {
	private fun testInitParams(
		directory: String = "/does/not/exist",
		forceSync: Boolean = false,
	) = InitializeProjectParams(
		directory = directory,
		needsGradleSync = forceSync,
		buildId = BuildId.Unknown,
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
	fun `GIVEN the same project twice THEN the connector can be reused`() {
		val server = ToolingApiServerImpl()

		// Two separately built objects describing the same connection, which is what the client
		// sends: every initialize request arrives freshly deserialized from JSON-RPC.
		val first = testInitParams()
		val second = testInitParams()

		// The guard used to be `params == lastInitParams`. InitializeProjectParams declares no
		// equals, so that was reference equality between two distinct objects and answered false
		// every time -- which meant forceConnect on every re-initialize, which disconnects the open
		// connector, and GradleConnector.disconnect() sends the running daemon StopWhenIdle. The
		// client re-initializes on every activity recreate outside EditorActivityKt's
		// configChanges, so a theme, locale or display-size change killed the warm daemon and the
		// next build paid a cold start (ADFA-5589).
		assertThat(first == second).isFalse()
		assertThat(server.describesSameConnection(first, second)).isTrue()
	}

	@Test
	fun `GIVEN a different project directory THEN the connector cannot be reused`() {
		val server = ToolingApiServerImpl()

		// A connector is bound to its project directory, so reusing one across projects would run
		// the next build against the previous project's connection.
		assertThat(
			server.describesSameConnection(
				testInitParams(directory = "/does/not/exist"),
				testInitParams(directory = "/somewhere/else"),
			),
		).isFalse()
	}

	@Test
	fun `GIVEN a different Gradle distribution THEN the connector cannot be reused`() {
		val server = ToolingApiServerImpl()

		// The other thing a connector is bound to. Reusing one here would silently build with the
		// distribution the user had just changed away from.
		val wrapper = testInitParams()
		val installation =
			InitializeProjectParams(
				directory = wrapper.directory,
				gradleDistribution = GradleDistributionParams.forVersion("8.14.3"),
				needsGradleSync = false,
				buildId = BuildId.Unknown,
			)

		assertThat(server.describesSameConnection(wrapper, installation)).isFalse()
	}

	@Test
	fun `GIVEN nothing initialized yet THEN the connector cannot be reused`() {
		val server = ToolingApiServerImpl()

		assertThat(server.describesSameConnection(null, testInitParams())).isFalse()
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
}
