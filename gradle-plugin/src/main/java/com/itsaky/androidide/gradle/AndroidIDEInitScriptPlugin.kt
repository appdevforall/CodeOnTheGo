/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.gradle

import com.itsaky.androidide.buildinfo.BuildInfo
import org.adfa.constants.COGO_GRADLE_PLUGIN_JAR_NAME
import org.adfa.constants.COGO_GRADLE_PLUGIN_PATH
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.invocation.Gradle
import org.gradle.api.logging.Logging
import java.io.File
import java.net.URLClassLoader

const val MAX_LOGFILE_COUNT = 2

/**
 * Plugin for the AndroidIDE's Gradle Init Script.
 *
 * @author Akash Yadav
 */
class AndroidIDEInitScriptPlugin : Plugin<Gradle> {
	companion object {
		private val logger = Logging.getLogger(AndroidIDEInitScriptPlugin::class.java)

		/**
		 * Picks what to put on the root buildscript classpath so that subprojects can resolve
		 * [BuildInfo.PACKAGE_NAME] by plugin ID.
		 *
		 * An init script's own classpath does NOT reach project plugin resolution on any
		 * Gradle version - a plugin ID only resolves from a buildscript classpath - so this
		 * injection is the sole mechanism that makes `pluginManager.apply(id)` work below.
		 *
		 * Prefers the jar the IDE ships (unchanged on-device behavior), and falls back to
		 * whatever the init script was loaded from, so the mechanism also works wherever
		 * that path does not exist (tests, or a relocated IDE home). Injecting a path that
		 * does not exist is a silent no-op in Gradle, which surfaces much later as a
		 * confusing `Plugin with id '...' not found`, so an empty result fails loud here.
		 */
		internal fun resolvePluginClasspath(
			bundledJar: File,
			initScriptClasspath: List<File>,
		): List<File> {
			if (bundledJar.isFile) {
				return listOf(bundledJar)
			}

			val fallback = initScriptClasspath.filter(File::exists)
			if (fallback.isNotEmpty()) {
				return fallback
			}

			throw GradleException(
				"Cannot inject the '${BuildInfo.PACKAGE_NAME}' plugin: no plugin jar at " +
					"'${bundledJar.absolutePath}' and the init script classpath is empty.",
			)
		}
	}

	override fun apply(target: Gradle) {
		removeDaemonLogs(target)

		target.settingsEvaluated { settings ->
			settings.pluginManager.apply(COTGSettingsPlugin::class.java)
		}

		target.rootProject { rootProject ->
			val classpath =
				resolvePluginClasspath(
					File(COGO_GRADLE_PLUGIN_PATH, COGO_GRADLE_PLUGIN_JAR_NAME),
					initScriptClasspath(),
				)
			logger.info("Injecting plugin classpath into the root buildscript: $classpath")
			rootProject.buildscript.dependencies.add("classpath", rootProject.files(classpath))
		}

		target.projectsLoaded { gradle ->
			gradle.rootProject.subprojects { sub ->
				if (!sub.buildFile.exists()) {
					// For subproject ':nested:module',
					// ':nested' is represented as a 'Project', but it may or may not have a buildscript file
					// if the project doesn't have a buildscript, then the plugins should not be applied
					return@subprojects
				}

				sub.afterEvaluate {
					logger.info("Trying to apply plugin '${BuildInfo.PACKAGE_NAME}' to project '${sub.path}'")
					sub.pluginManager.apply(BuildInfo.PACKAGE_NAME)
				}
			}
		}
	}

	/** The files this plugin itself was loaded from, i.e. the init script's classpath. */
	private fun initScriptClasspath(): List<File> {
		val loader = javaClass.classLoader as? URLClassLoader ?: return emptyList()
		return loader.urLs.mapNotNull { url -> runCatching { File(url.toURI()) }.getOrNull() }
	}

	private fun removeDaemonLogs(gradle: Gradle) {
		// Get the Gradle user home directory
		val gradleUserHomeDir = gradle.gradleUserHomeDir

		// Get the current Gradle version
		val currentGradleVersion = gradle.gradleVersion
		val logsDir = File(gradleUserHomeDir, "daemon/$currentGradleVersion")

		if (logsDir.exists() && logsDir.isDirectory) {
			logger.lifecycle("Code On the Go clean logs of gradle ($currentGradleVersion) task running....")

			// Filter and iterate over log files, sorted by last modified date
			logsDir
				.listFiles()
				?.filter { it.isFile && it.name.endsWith(".log") }
				?.sortedByDescending { it.lastModified() }
				?.drop(MAX_LOGFILE_COUNT)
				?.forEach { logFile ->
					logger.lifecycle("deleting log: ${logFile.name}")
					logFile.delete()
				}
		} else {
			logger.lifecycle(
				"No deletions made, number of log files does not" +
					" exceed ($MAX_LOGFILE_COUNT) for gradle ($currentGradleVersion).",
			)
		}
	}
}
