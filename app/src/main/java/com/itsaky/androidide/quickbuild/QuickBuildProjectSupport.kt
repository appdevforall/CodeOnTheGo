package com.itsaky.androidide.quickbuild

import androidx.annotation.StringRes
import com.itsaky.androidide.resources.R
import com.itsaky.androidide.tooling.api.GradlePluginConfig
import java.io.File
import java.io.StringReader
import java.util.Properties

/**
 * The reasons Quick Build refuses a project up front, as string resources.
 *
 * Detecting each before the proxy app build runs turns a raw Gradle failure into a friendly,
 * actionable message. Resources rather than text, so the refusals localize with the rest of the IDE
 * and these functions stay resolvable without a Context (the caller owns that).
 */
object QuickBuildProjectSupport {
	/**
	 * Quick Build's artifact is a runnable proxy app APK, and a plugin project builds a `.cgp`
	 * instead - nothing to install or launch, and no `:app` for the task path to name.
	 *
	 * @param isPluginProject whether the open project builds a plugin package.
	 * @return the refusal message, or null when the project type is supported.
	 */
	@StringRes
	fun unsupportedProjectTypeMessage(isPluginProject: Boolean): Int? =
		if (isPluginProject) {
			R.string.quick_build_unsupported_plugin_project
		} else {
			null
		}

	/**
	 * A successful proxy app build with no launchable Activity (the No-Activity template) has
	 * nothing to install or launch. Unlike [unsupportedProjectTypeMessage] this is only knowable
	 * AFTER the build, since `setup.json`'s `entryActivity` comes from the real manifest merge.
	 *
	 * @param entryActivity the launcher activity the proxy app build reported, or null if none.
	 * @return the refusal message, or null when there is an activity to launch.
	 */
	@StringRes
	fun noLaunchableActivityMessage(entryActivity: String?): Int? =
		if (entryActivity == null) {
			R.string.quick_build_no_launchable_activity
		} else {
			null
		}

	/**
	 * Quick Build only exists for DEBUGGABLE variants, so a release selection would run a full
	 * release build (minified, often unsignable on device) only to end in a missing `setup.json`.
	 *
	 * The project model carries no `debuggable` flag, so this reads AGP's variant NAME and matches
	 * only `release`. Deliberately narrow: a custom build type may well be debuggable, so those
	 * fall through to the build and, if the plugin really did skip them, to the missing-setup
	 * message.
	 *
	 * @param variantName the variant the Build Variants sidebar has selected.
	 * @return the refusal message, or null when the variant may be debuggable.
	 */
	@StringRes
	fun nonDebuggableVariantMessage(variantName: String): Int? =
		if (variantName == "release" || variantName.endsWith("Release")) {
			R.string.quick_build_non_debuggable_variant
		} else {
			null
		}

	/**
	 * A project with `cogo.sqliteDebugger=true` is moved to requery by the Gradle plugin, which
	 * never applies Quick Build to it (its payload would compile the source without the move). The
	 * IDE must not provision it either: the build would succeed and then end in a "variant setup
	 * missing" message that blames the variant.
	 *
	 * @param sqliteDebuggerEnabled see [isSqliteDebuggerEnabled].
	 * @return the refusal message, or null when Quick Build may run.
	 */
	@StringRes
	fun sqliteDebuggerMessage(sqliteDebuggerEnabled: Boolean): Int? =
		if (sqliteDebuggerEnabled) {
			R.string.quick_build_disabled_sqlite_debugger
		} else {
			null
		}

	/**
	 * Whether the root `gradle.properties` text opts in, decided as the Gradle plugin does: the
	 * value is exactly `true`.
	 *
	 * @param gradleProperties the file's text, or null when the project has none.
	 */
	fun isSqliteDebuggerEnabled(gradleProperties: String?): Boolean {
		if (gradleProperties == null) return false
		val properties = Properties()
		return try {
			properties.load(StringReader(gradleProperties))
			properties.getProperty(GradlePluginConfig.PROPERTY_SQLITE_DEBUGGER_ENABLED) == "true"
		} catch (e: IllegalArgumentException) {
			false
		}
	}

	/** [isSqliteDebuggerEnabled] for the `gradle.properties` in [projectRoot]; false when it cannot be read. */
	fun isSqliteDebuggerEnabled(projectRoot: File): Boolean =
		isSqliteDebuggerEnabled(
			runCatching { File(projectRoot, "gradle.properties").takeIf { it.isFile }?.readText() }.getOrNull(),
		)
}
