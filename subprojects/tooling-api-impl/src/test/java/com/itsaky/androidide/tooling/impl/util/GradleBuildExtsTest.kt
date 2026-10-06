package com.itsaky.androidide.tooling.impl.util

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.tooling.api.messages.ClientGradleBuildConfig
import com.itsaky.androidide.tooling.api.messages.GradleBuildParams
import io.mockk.every
import io.mockk.mockk
import org.gradle.tooling.BuildLauncher
import org.gradle.tooling.events.OperationType
import org.gradle.tooling.events.ProgressListener
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * The argument order [configureFrom] gives a launcher. A task option such as `--tests` only binds
 * to a task named before it in the arguments; with the task in `forTasks` instead, Gradle rejects
 * it as "Unknown command-line option '--tests'" (ADFA-6337).
 */
@RunWith(JUnit4::class)
class GradleBuildExtsTest {
	private val arguments = mutableListOf<String>()

	private val launcher =
		mockk<BuildLauncher>(relaxed = true).also { launcher ->
			// A relaxed mock answers the generic setters with the wrong type; they return the launcher.
			every { launcher.setStandardError(any()) } returns launcher
			every { launcher.setStandardOutput(any()) } returns launcher
			every { launcher.setStandardInput(any()) } returns launcher
			every {
				launcher.addProgressListener(any<ProgressListener>(), any<Set<OperationType>>())
			} returns launcher
			every { launcher.addJvmArguments(any<Iterable<String>>()) } returns launcher
			every { launcher.addArguments(any<Iterable<String>>()) } answers {
				arguments += firstArg<Iterable<String>>()
				launcher
			}
		}

	@Test
	fun givenTasksAndATaskOption_whenConfiguring_thenTheTasksComeBetweenClientAndBuildArguments() {
		launcher.configureFrom(
			clientConfig = ClientGradleBuildConfig(GradleBuildParams(gradleArgs = listOf("--offline"))),
			buildParams = GradleBuildParams(gradleArgs = listOf("--tests", "com.example.FooTest")),
			tasks = listOf(":app:testDebugUnitTest"),
		)

		assertThat(arguments)
			.containsExactly("--offline", ":app:testDebugUnitTest", "--tests", "com.example.FooTest")
			.inOrder()
	}

	@Test
	fun givenBlankTasks_whenConfiguring_thenTheyAreDropped() {
		launcher.configureFrom(
			buildParams = GradleBuildParams(gradleArgs = listOf("--info")),
			tasks = listOf("build", " "),
		)

		assertThat(arguments).containsExactly("build", "--info").inOrder()
	}

	@Test
	fun givenNoTasks_whenConfiguring_thenOnlyTheArgumentsArePassed() {
		launcher.configureFrom(buildParams = GradleBuildParams(gradleArgs = listOf("-Pa=b")))

		assertThat(arguments).containsExactly("-Pa=b")
	}
}
