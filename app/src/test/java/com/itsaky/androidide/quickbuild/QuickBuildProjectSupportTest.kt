package com.itsaky.androidide.quickbuild

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * ADFA-4128 Bug 3 (UX half): a plugin project's artifact is a `.cgp`, not a runnable
 * app, so Quick Build should refuse with a friendly message instead of running the
 * setup build into a raw Gradle failure.
 */
class QuickBuildProjectSupportTest {
	@Test
	fun `plugin projects get a friendly unsupported-project message`() {
		val message = QuickBuildProjectSupport.unsupportedProjectTypeMessage(isPluginProject = true)

		assertThat(message).isNotNull()
		assertThat(message).contains("plugin")
	}

	@Test
	fun `non-plugin projects are not blocked`() {
		val message = QuickBuildProjectSupport.unsupportedProjectTypeMessage(isPluginProject = false)

		assertThat(message).isNull()
	}

	@Test
	fun `a null entryActivity gets a friendly no-launchable-activity message, not a generic failure`() {
		// ADFA-4128 Bug 10: setup.json without entryActivity + a successful setup
		// build must surface this specific, actionable message - not the generic
		// "Quick Build setup build failed" the misclassification used to produce.
		val message = QuickBuildProjectSupport.noLaunchableActivityMessage(entryActivity = null)

		assertThat(message).isNotNull()
		assertThat(message).contains("launchable Activity")
	}

	@Test
	fun `a project with an entry activity is not blocked`() {
		val message =
			QuickBuildProjectSupport.noLaunchableActivityMessage(
				entryActivity = "com.example.app.MainActivity",
			)

		assertThat(message).isNull()
	}
}
