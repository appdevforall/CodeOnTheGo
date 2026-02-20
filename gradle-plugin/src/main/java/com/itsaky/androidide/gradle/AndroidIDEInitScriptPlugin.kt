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
import com.itsaky.androidide.tooling.api.LogSenderConfig._PROPERTY_IS_TEST_ENV
import com.itsaky.androidide.tooling.api.LogSenderConfig._PROPERTY_MAVEN_LOCAL_REPOSITORY
import org.adfa.constants.ANDROIDIDE_HOME
import org.adfa.constants.MAVEN_LOCAL_REPOSITORY
import org.gradle.StartParameter
import org.gradle.api.Plugin
import org.gradle.api.artifacts.dsl.RepositoryHandler
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.initialization.Settings
import org.gradle.api.invocation.Gradle
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import java.io.File
import java.net.URI

const val MAX_LOGFILE_COUNT = 2

/**
 * Plugin for the AndroidIDE's Gradle Init Script.
 *
 * @author Akash Yadav
 */
class AndroidIDEInitScriptPlugin : Plugin<Gradle> {
	companion object {
		private val logger = Logging.getLogger(AndroidIDEInitScriptPlugin::class.java)
	}

	override fun apply(target: Gradle) {
		removeDaemonLogs(target)

		target.settingsEvaluated { settings ->
			settings.addLocalRepos()
		}

		target.rootProject { rootProject ->
			rootProject.buildscript.apply {
				dependencies.apply {
					add(
						"classpath",
						rootProject.files("$ANDROIDIDE_HOME/plugin/cogo-plugin.jar"),
					)
				}
			}
		}

		target.projectsLoaded { gradle ->
			gradle.rootProject.subprojects { sub ->
				if (!sub.buildFile.exists()) {
					// For subproject ':nested:module',
					// ':nested' represented as a 'Project', but it may or may not have a buildscript file
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

	private fun removeDaemonLogs(gradle: Gradle) {
		// Get the Gradle user home directory
		val gradleUserHomeDir = gradle.gradleUserHomeDir

		// Get the current Gradle version
		val currentGradleVersion = gradle.gradleVersion
		val logsDir = File(gradleUserHomeDir, "daemon/$currentGradleVersion")

		if (logsDir.exists() && logsDir.isDirectory) {
			logger.lifecycle("CoGo clean logs of gradle ($currentGradleVersion) task running....")

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

	private fun Settings.addLocalRepos() {
		// Add our local maven repo, always.
		addLocalRepos(mavenLocalRepos = listOf(MAVEN_LOCAL_REPOSITORY))

		// Then check if we need to add additional repos, based on whether
		// we're in a test environment
		val (isTestEnv, mavenLocalRepos) = getTestEnvProps(startParameter)
		if (isTestEnv) {
			addLocalRepos(mavenLocalRepos = mavenLocalRepos)
		}
	}

	private fun RepositoryHandler.addLocalRepos(repos: List<String>) {
		repos.forEach { repo ->
			addLocalMavenRepoIfMissing(logger, repo)
		}
	}

	@Suppress("UnstableApiUsage")
	private fun Settings.addLocalRepos(mavenLocalRepos: List<String>) {
		dependencyResolutionManagement.repositories { repositories ->
			repositories.addLocalRepos(mavenLocalRepos)
		}

		pluginManagement.repositories { repositories ->
			repositories.addLocalRepos(mavenLocalRepos)
		}
	}

	private fun getTestEnvProps(startParameter: StartParameter): Pair<Boolean, List<String>> =
		startParameter.run {
			val isTestEnv =
				projectProperties.containsKey(_PROPERTY_IS_TEST_ENV) &&
					projectProperties[_PROPERTY_IS_TEST_ENV].toString().toBoolean()
			val mavenLocalRepos =
				projectProperties.getOrDefault(_PROPERTY_MAVEN_LOCAL_REPOSITORY, "")

			isTestEnv to mavenLocalRepos.split(':').toList().filter { it.isNotBlank() }
		}
}

private fun RepositoryHandler.addLocalMavenRepoIfMissing(
	logger: Logger,
	path: String,
) {
	val dir = File(path)
	require(dir.isDirectory) { "Repo not found: $path" }

	val uri = dir.toURI()

	addMavenRepoIfMissing(logger, uri)
}

private fun RepositoryHandler.addMavenRepoIfMissing(
	logger: Logger,
	uri: URI,
) {
	if (none { it is MavenArtifactRepository && it.url == uri }) {
		logger.info("Adding maven repository: $uri")
		maven { it.url = uri }
	}
}
