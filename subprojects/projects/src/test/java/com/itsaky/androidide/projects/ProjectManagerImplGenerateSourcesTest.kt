package com.itsaky.androidide.projects

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.projects.builder.BuildService
import com.itsaky.androidide.tooling.api.messages.result.TaskExecutionResult
import com.itsaky.androidide.utils.flashError
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CompletableFuture

/**
 * Pins the [ProjectManagerImpl.generateSources] Boolean contract: false means the request
 * did nothing (null build service, tooling server down, or a Gradle build already holding
 * the slot), true means the tasks were handed to the tooling server. The Quick Build
 * generate-sources deferral (added later in this stack) keys its retry off this value, so
 * a silently-flipped refusal would break it without any other test noticing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ProjectManagerImplGenerateSourcesTest {
	@Before
	fun setUp() {
		// The server-down branch flashes an error bar; there is no foreground activity in
		// this test, so stub the top-level flashError out.
		mockkStatic("com.itsaky.androidide.utils.FlashbarUtilsKt")
		justRun { flashError(any<Int>()) }
	}

	@After
	fun tearDown() {
		unmockkStatic("com.itsaky.androidide.utils.FlashbarUtilsKt")
	}

	@Test
	fun `a null build service returns false`() {
		assertThat(ProjectManagerImpl().generateSources(null)).isFalse()
	}

	@Test
	fun `a stopped tooling server returns false and dispatches nothing`() {
		val service = mockk<BuildService>()
		every { service.isToolingServerStarted() } returns false

		assertThat(ProjectManagerImpl().generateSources(service)).isFalse()
		verify(exactly = 0) { service.executeTasks(*anyVararg<String>()) }
	}

	@Test
	fun `a build already in progress returns false and dispatches nothing`() {
		val service = mockk<BuildService>()
		every { service.isToolingServerStarted() } returns true
		every { service.isBuildInProgress } returns true

		assertThat(ProjectManagerImpl().generateSources(service)).isFalse()
		verify(exactly = 0) { service.executeTasks(*anyVararg<String>()) }
	}

	@Test
	fun `an idle tooling server dispatches the tasks and returns true`() {
		val service = mockk<BuildService>()
		every { service.isToolingServerStarted() } returns true
		every { service.isBuildInProgress } returns false
		// A fresh manager has no workspace, so the dispatched task list is empty - stub and
		// verify that exact call rather than a vararg matcher.
		every { service.executeTasks() } returns
			CompletableFuture.completedFuture<TaskExecutionResult>(null)

		assertThat(ProjectManagerImpl().generateSources(service)).isTrue()
		verify(exactly = 1) { service.executeTasks() }
	}
}
