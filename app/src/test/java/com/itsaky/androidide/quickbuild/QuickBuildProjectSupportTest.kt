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

	@Test
	fun `a release variant is refused with the pick-a-debug-variant guidance`() {
		// The Gradle plugin only configures Quick Build for debuggable variants, so a release
		// selection would otherwise run a whole release build and end in "setup.json not
		// found" - which names nothing the user can act on.
		assertThat(QuickBuildProjectSupport.nonDebuggableVariantMessage("release"))
			.isEqualTo(R.string.quick_build_non_debuggable_variant)
	}

	@Test
	fun `a flavored release variant is refused too`() {
		assertThat(QuickBuildProjectSupport.nonDebuggableVariantMessage("demoRelease"))
			.isEqualTo(R.string.quick_build_non_debuggable_variant)
	}

	@Test
	fun `debug variants are not blocked`() {
		assertThat(QuickBuildProjectSupport.nonDebuggableVariantMessage("debug")).isNull()
		assertThat(QuickBuildProjectSupport.nonDebuggableVariantMessage("demoDebug")).isNull()
	}

	@Test
	fun `a custom build type is not blocked up front`() {
		// A custom build type may well be debuggable and the project model carries no flag to
		// tell, so these run the build rather than being refused on their name. Blocking them
		// would make a valid configuration unusable.
		assertThat(QuickBuildProjectSupport.nonDebuggableVariantMessage("staging")).isNull()
		assertThat(QuickBuildProjectSupport.nonDebuggableVariantMessage("demoStaging")).isNull()
	}
}
