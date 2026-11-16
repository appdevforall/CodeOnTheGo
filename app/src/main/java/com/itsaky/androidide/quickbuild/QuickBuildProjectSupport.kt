package com.itsaky.androidide.quickbuild

import androidx.annotation.StringRes
import com.itsaky.androidide.resources.R
import com.itsaky.androidide.tooling.api.GradlePluginConfig
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
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
	 * Whether one `gradle.properties` text opts in, decided as the Gradle plugin does: the value is
	 * exactly `true`.
	 *
	 * @param gradleProperties the file's text, or null when there is none.
	 */
	fun isSqliteDebuggerEnabled(gradleProperties: String?): Boolean = gradleProperties != null && propertyValue(gradleProperties) == "true"

	/**
	 * Whether the project opts in, from the same files the Gradle plugin's `findProperty` sees in
	 * Code on the Go's builds: the Gradle user home `gradle.properties` (`GRADLE_USER_HOME`, which
	 * overrides the project files), the app module's, and the root's. Gradle loads a
	 * `gradle.properties` from every project directory into that project, and a subproject also
	 * inherits its parent's properties, so the first of these that defines the property wins.
	 * `-P` arguments are not read: Code on the Go passes none for this property.
	 *
	 * A file that cannot be read is reported to [onReadFailure] and counts as not defining it.
	 *
	 * @param moduleDirs the directories of the app modules the plugin is applied to.
	 */
	fun isSqliteDebuggerEnabled(
		projectRoot: File,
		gradleUserHome: File?,
		moduleDirs: List<File> = emptyList(),
		onReadFailure: (File, Throwable) -> Unit = { file, error ->
			log.warn("Cannot read {}; treating it as not defining the SQLite debugger opt-in", file, error)
		},
		readText: (File) -> String = File::readText,
	): Boolean {
		val files =
			listOfNotNull(gradleUserHome) + moduleDirs + projectRoot
		for (entry in files) {
			val file = File(entry, "gradle.properties")
			val text =
				try {
					file.takeIf { it.isFile }?.let(readText)
				} catch (e: IOException) {
					onReadFailure(file, e)
					null
				} ?: continue
			val value = propertyValue(text) ?: continue
			return value == "true"
		}
		return false
	}

	private fun propertyValue(gradleProperties: String): String? =
		try {
			Properties()
				.apply { load(StringReader(gradleProperties)) }
				.getProperty(GradlePluginConfig.PROPERTY_SQLITE_DEBUGGER_ENABLED)
		} catch (e: IllegalArgumentException) {
			null
		}

	private val log = LoggerFactory.getLogger("QB-Support")
}
