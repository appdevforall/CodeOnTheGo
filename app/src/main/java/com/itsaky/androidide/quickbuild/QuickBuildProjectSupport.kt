package com.itsaky.androidide.quickbuild

import androidx.annotation.StringRes
import com.itsaky.androidide.resources.R

/**
 * Quick Build's artifact is a runnable proxy app APK; a plugin project's build output is a
 * `.cgp` package instead, so there is nothing to install or launch. Detecting this
 * before the proxy app build runs turns a raw Gradle failure (a plugin
 * project's single module has no `:app`, so the proxy app build's task-path composition
 * hits `TaskSelectionException`) into a friendly, actionable message.
 *
 * Returns string RESOURCES rather than text, so the refusals localize with the rest of the IDE
 * and these functions stay resolvable without a Context (the caller owns that).
 */
object QuickBuildProjectSupport {
	@StringRes
	fun unsupportedProjectTypeMessage(isPluginProject: Boolean): Int? =
		if (isPluginProject) {
			R.string.quick_build_unsupported_plugin_project
		} else {
			null
		}

	/**
	 * A successful proxy app build with no launchable Activity (e.g.
	 * the No-Activity template) has nothing for Quick Build to install or launch.
	 * Unlike [unsupportedProjectTypeMessage], this can only be known AFTER the proxy app
	 * build runs (`setup.json`'s `entryActivity` comes from the real manifest merge),
	 * so it's checked once the proxy app build's [org.appdevforall.cotg.quickbuild.data.ProxyAppInfo]
	 * is in hand, so it reads as a friendly refusal rather than a build failure.
	 */
	@StringRes
	fun noLaunchableActivityMessage(entryActivity: String?): Int? =
		if (entryActivity == null) {
			R.string.quick_build_no_launchable_activity
		} else {
			null
		}
}
