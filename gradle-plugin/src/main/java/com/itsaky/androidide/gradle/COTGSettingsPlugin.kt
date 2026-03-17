package com.itsaky.androidide.gradle

import com.itsaky.androidide.tooling.api.GradlePluginConfig._PROPERTY_IS_TEST_ENV
import com.itsaky.androidide.tooling.api.GradlePluginConfig._PROPERTY_MAVEN_LOCAL_REPOSITORY
import org.adfa.constants.MAVEN_LOCAL_REPOSITORY
import org.gradle.StartParameter
import org.gradle.api.Plugin
import org.gradle.api.artifacts.dsl.RepositoryHandler
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.initialization.Settings
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import java.io.File
import java.net.URI

class COTGSettingsPlugin : Plugin<Settings> {
	private val logger = Logging.getLogger(COTGSettingsPlugin::class.java)

	override fun apply(target: Settings) {
		if (target.gradle.parent != null) {
			// only apply the settings plugin to the root Gradle build
			return
		}

		logger.info("Plugin instance: ${System.identityHashCode(this)}")
		// Add our local maven repo, always.
		val allLocalRepos = mutableListOf(MAVEN_LOCAL_REPOSITORY)

		// Then check if we need to add additional repos, based on whether
		// we're in a test environment
		val (isTestEnv, mavenLocalRepos) = getTestEnvProps(target.startParameter)
		if (isTestEnv) {
			allLocalRepos += mavenLocalRepos
		}

		target.addLocalRepos(allLocalRepos)
	}

	private fun RepositoryHandler.addLocalRepos(repos: List<String>) {
		repos.forEach { repo ->
			addLocalMavenRepoIfMissing(logger, repo)
		}
	}

	@Suppress("UnstableApiUsage")
	private fun Settings.addLocalRepos(mavenLocalRepos: List<String>) {
		dependencyResolutionManagement.repositories.addLocalRepos(mavenLocalRepos)
		pluginManagement.repositories.addLocalRepos(mavenLocalRepos)
	}

	private fun getTestEnvProps(startParameter: StartParameter): Pair<Boolean, List<String>> =
		startParameter.run {
			val isTestEnv =
				projectProperties.containsKey(_PROPERTY_IS_TEST_ENV) &&
					projectProperties[_PROPERTY_IS_TEST_ENV].toString().toBoolean()
			val mavenLocalRepos =
				projectProperties.getOrDefault(_PROPERTY_MAVEN_LOCAL_REPOSITORY, "")

			isTestEnv to
				mavenLocalRepos
					.split(File.pathSeparatorChar)
					.toList()
					.filter { it.isNotBlank() }
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
