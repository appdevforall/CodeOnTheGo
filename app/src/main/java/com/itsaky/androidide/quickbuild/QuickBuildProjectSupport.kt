package com.itsaky.androidide.quickbuild

/**
 * Quick Build's artifact is a runnable test APK; a plugin project's build output is a
 * `.cgp` package instead, so there is nothing to install or launch. Detecting this
 * before the setup build runs turns a raw Gradle failure (ADFA-4128 Bug 3: a plugin
 * project's single module has no `:app`, so the setup build's task-path composition
 * hits `TaskSelectionException`) into a friendly, actionable message.
 */
object QuickBuildProjectSupport {
	fun unsupportedProjectTypeMessage(isPluginProject: Boolean): String? =
		if (isPluginProject) {
			"Quick Build isn't available for plugin projects - the build output is a " +
				".cgp package, not a runnable app. Use Run/Debug to build the plugin instead."
		} else {
			null
		}

	/**
	 * A successful setup build with no launchable Activity (ADFA-4128 Bug 10 - e.g.
	 * the No-Activity template) has nothing for Quick Build to install or launch.
	 * Unlike [unsupportedProjectTypeMessage], this can only be known AFTER the setup
	 * build runs (`setup.json`'s `entryActivity` comes from the real manifest merge),
	 * so it's checked once the setup build's [org.appdevforall.cotg.quickbuild.data.SetupInfo]
	 * is in hand - turning what used to read as a build failure into a friendly refusal.
	 */
	fun noLaunchableActivityMessage(entryActivity: String?): String? =
		if (entryActivity == null) {
			"Quick Build needs a launchable Activity in this project - none was found. " +
				"Use Run/Debug to build and inspect it instead."
		} else {
			null
		}
}
