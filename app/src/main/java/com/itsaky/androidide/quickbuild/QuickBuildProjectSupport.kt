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
	 * The directories whose `gradle.properties` Gradle's `findProperty` reads for the project at
	 * [modulePath], in Gradle's precedence: the module's own directory, the root's, then each parent
	 * project's, nearest first. Gradle gives the root's value precedence over every parent's, so for
	 * `:group:app` the root's file comes before `:group`'s.
	 *
	 * A parent's directory comes from [projectDirs] (Gradle path to directory, from the project
	 * model, which lists the projects that have no Android plugin as well). A parent the model
	 * lacks is taken from the default layout: the module's directory walked up one level per path
	 * segment, which is wrong for a `projectDir` that `settings.gradle` has remapped.
	 *
	 * @param projectRoot the root project's directory, second after the module's.
	 * @param modulePath the module's Gradle path, such as `:group:app`.
	 * @param moduleDir the module's own directory.
	 */
	fun propertyDirs(
		projectRoot: File,
		modulePath: String,
		moduleDir: File,
		projectDirs: Map<String, File> = emptyMap(),
	): List<File> {
		val segments = modulePath.split(':').filter { it.isNotEmpty() }
		val dirs = LinkedHashSet<File>()
		dirs += moduleDir
		dirs += projectRoot
		var walked: File? = moduleDir
		for (depth in segments.size - 1 downTo 1) {
			walked = walked?.parentFile
			(projectDirs[":" + segments.take(depth).joinToString(":")] ?: walked)?.let(dirs::add)
		}
		return dirs.toList()
	}

	/**
	 * Whether the module whose directories are [projectDirs] opts in to `cogo.sqliteDebugger=true`,
	 * in which case Quick Build must not provision it: the Gradle plugin moves such a module to
	 * requery and never applies Quick Build to it, so a build would succeed and end in a "variant
	 * setup missing" message that blames the variant. Decided for the module Quick Build builds
	 * alone, as Gradle decides it: another app module's value does not matter.
	 *
	 * Reads the files the Gradle plugin's `findProperty` sees for that module in Code on the Go's
	 * builds, and the first one that defines the property decides: the Gradle user home
	 * `gradle.properties` (`GRADLE_USER_HOME`, which overrides the project files), then [projectDirs]
	 * in order (see [propertyDirs]: the module, the root, then its parent projects). Gradle loads a
	 * `gradle.properties` from every project directory into that project, and a subproject also
	 * inherits its parents' properties, the root's before any other parent's. `-P` arguments are not read: Code on the Go passes none for
	 * this property.
	 *
	 * A file that cannot be read is reported to [onReadFailure] and counts as not defining it.
	 *
	 * @param gradleUserHome the Gradle user home, or null when there is none.
	 * @param projectDirs the module's directory, the root's, then its parents', in [propertyDirs]' order.
	 */
	fun isSqliteDebuggerEnabled(
		gradleUserHome: File?,
		projectDirs: List<File>,
		onReadFailure: (File, Throwable) -> Unit = { file, error ->
			log.warn("Cannot read {}; treating it as not defining the SQLite debugger opt-in", file, error)
		},
		readText: (File) -> String = File::readText,
	): Boolean {
		for (entry in listOfNotNull(gradleUserHome) + projectDirs) {
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
