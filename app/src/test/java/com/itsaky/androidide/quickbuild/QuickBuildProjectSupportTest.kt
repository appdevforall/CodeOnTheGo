package com.itsaky.androidide.quickbuild

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.resources.R
import org.junit.Test

/**
 * a plugin project's artifact is a `.cgp`, not a runnable
 * app, so Quick Build should refuse with a friendly message instead of running the
 * proxy app build into a raw Gradle failure.
 *
 * The refusals are string RESOURCES, not literals, so they localize with the rest of the IDE -
 * asserted by id here, which keeps these checks JVM-only (no Context, no Robolectric).
 */
class QuickBuildProjectSupportTest {
	@Test
	fun `plugin projects get a friendly unsupported-project message`() {
		val message = QuickBuildProjectSupport.unsupportedProjectTypeMessage(isPluginProject = true)

		assertThat(message).isEqualTo(R.string.quick_build_unsupported_plugin_project)
	}

	@Test
	fun `non-plugin projects are not blocked`() {
		val message = QuickBuildProjectSupport.unsupportedProjectTypeMessage(isPluginProject = false)

		assertThat(message).isNull()
	}

	@Test
	fun `a null entryActivity gets a friendly no-launchable-activity message, not a generic failure`() {
		// setup.json without entryActivity + a successful proxy app
		// build must surface this specific, actionable message - not the generic
		// "Quick Build proxy app build failed" a misclassification would produce.
		val message = QuickBuildProjectSupport.noLaunchableActivityMessage(entryActivity = null)

		assertThat(message).isEqualTo(R.string.quick_build_no_launchable_activity)
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
