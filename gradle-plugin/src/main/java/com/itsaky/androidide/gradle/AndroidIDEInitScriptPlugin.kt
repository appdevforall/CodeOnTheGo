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
import org.gradle.StartParameter
import org.gradle.api.Plugin
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.dsl.RepositoryHandler
import org.gradle.api.initialization.Settings
import org.gradle.api.invocation.Gradle
import org.gradle.api.logging.Logging
import java.io.File
import java.io.FileNotFoundException
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

  /**
   * Keywords: [gradle, agp, androidGradlePlugin, classpath, build ]
   * It seeme like this method adds custom android-gradle-plugin to the classpath.
   * Without explicitly adding it to any gradle files.
   * This script is a prat of androidIde. So even if child process fails to build,
   * it only means that androidIDE toolchain was not satisfied.
   * So far I can't find
   * @see VERSION_NAME_DOWNLOAD
   * gradle .jar and it seems to be required.
   * This script has no direct usage by AS search, but is invoked from string and in gradle tasks.
   */
  override fun apply(target: Gradle) {
    removeDaemonLogs(target)

    // NOTE disable access to non-local repos

    target.rootProject { rootProject ->
      rootProject.buildscript.apply {
        dependencies.apply {
          add("classpath", rootProject.files("$ANDROIDIDE_HOME/plugin/cogo-plugin.jar"))
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
    // logger.lifecycle("#@^*( Applyingg Clean Plugin")
    // Get the Gradle user home directory
    val gradleUserHomeDir = gradle.gradleUserHomeDir

    // Get the current Gradle version
    val currentGradleVersion = gradle.gradleVersion
    val logsDir = File(gradleUserHomeDir, "daemon/$currentGradleVersion")

    if (logsDir.exists() && logsDir.isDirectory) {
      logger.lifecycle("CoGo clean logs of gradle ($currentGradleVersion) task running....")

      // Filter and iterate over log files, sorted by last modified date
      logsDir.listFiles()?.filter { it.isFile && it.name.endsWith(".log") }
        ?.sortedByDescending { it.lastModified() }
        ?.drop(MAX_LOGFILE_COUNT)
        ?.forEach { logFile ->
          logger.lifecycle("deleting log: ${logFile.name}")
          logFile.delete()
        }
    }
    else {
      logger.lifecycle("No deletions made, number of log files does not exceed ($MAX_LOGFILE_COUNT) for gradle ($currentGradleVersion). ")
    }
  }

  private fun Settings.addDependencyRepositories() {
    val (isTestEnv, mavenLocalRepos) = getTestEnvProps(startParameter)
    addDependencyRepositories(isTestEnv, mavenLocalRepos)
  }

  @Suppress("UnstableApiUsage")
  private fun Settings.addDependencyRepositories(
    isMavenLocalEnabled: Boolean,
    mavenLocalRepo: String
  ) {
    dependencyResolutionManagement.run {
      repositories.configureRepositories(isMavenLocalEnabled, mavenLocalRepo)
    }

    pluginManagement.apply {
      repositories.configureRepositories(isMavenLocalEnabled, mavenLocalRepo)
    }
  }

  private fun RepositoryHandler.addDependencyRepositories(startParams: StartParameter) {
    val (isTestEnv, mavenLocalRepos) = getTestEnvProps(startParams)
    configureRepositories(isTestEnv, mavenLocalRepos)
  }

  private fun getTestEnvProps(startParameter: StartParameter): Pair<Boolean, String> {
    return startParameter.run {
      val isTestEnv = projectProperties.containsKey(_PROPERTY_IS_TEST_ENV)
          && projectProperties[_PROPERTY_IS_TEST_ENV].toString().toBoolean()
      val mavenLocalRepos = projectProperties.getOrDefault(_PROPERTY_MAVEN_LOCAL_REPOSITORY, "")

      isTestEnv to mavenLocalRepos
    }
  }

  private fun RepositoryHandler.configureRepositories(
    isMavenLocalEnabled: Boolean,
    mavenLocalRepos: String
  ) {

    if (!isMavenLocalEnabled) {

      // For AndroidIDE CI builds
      maven { repository ->
        repository.url = URI.create(BuildInfo.SNAPSHOTS_REPOSITORY)
      }
    } else {
      logger.info("Using local maven repository for classpath resolution...")

      for (mavenLocalRepo in mavenLocalRepos.split(':')) {
        if (mavenLocalRepo.isBlank()) {
          mavenLocal()
        } else {
          logger.info("Local repository path: $mavenLocalRepo")

          val repo = File(mavenLocalRepo)
          if (!repo.exists() || !repo.isDirectory) {
            throw FileNotFoundException("Maven local repository '$mavenLocalRepo' not found")
          }

          maven { repository ->
            repository.url = repo.toURI()
          }
        }
      }
    }

    // for AGP API dependency
    google()

    maven { repository ->
      repository.setUrl(BuildInfo.PUBLIC_REPOSITORY)
    }

    mavenCentral()
    gradlePluginPortal()
  }
}