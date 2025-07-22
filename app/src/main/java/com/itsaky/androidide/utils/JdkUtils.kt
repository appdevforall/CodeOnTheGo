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

package com.itsaky.androidide.utils

import androidx.annotation.VisibleForTesting
import androidx.annotation.WorkerThread
import com.itsaky.androidide.app.IDEApplication
import com.itsaky.androidide.models.JdkDistribution
import com.itsaky.androidide.shell.executeProcessAsync
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files

/**
 * Utilities related to JDK installations.
 *
 * @author Akash Yadav
 */
object JdkUtils {

  private val log = LoggerFactory.getLogger(JdkUtils::class.java)

  /**
   * Finds the available JDK installations and returns the JAVA_HOME for each installation.
   */
  @JvmStatic
  @WorkerThread
  fun findJavaInstallations(): List<JdkDistribution> {

    // a valid JDK can be installed anywhere in the file system
    // however, we currently only check for installations that are located in $PREFIX/lib/jvm dir
    // TODO: Find a way to efficiently list all JDK installations, including those which are located
    //    outside of $PREFIX/lib/jvm
    return try {
      log.debug("Starting to find Java installations.")
      val optDir = File(Environment.PREFIX, "lib/jvm")
      log.debug("optDir: {}", optDir)
      if (!optDir.exists() || !optDir.isDirectory) {
        log.debug("optDir does not exist or is not a directory. optDir.exists(): {}, optDir.isDirectory(): {}", optDir.exists(), optDir.isDirectory)
        emptyList()
      } else {
        log.debug("optDir exists and is a directory.")
        optDir.listFiles()?.mapNotNull { dir ->
          log.debug("Processing directory: {}", dir)
          if (Files.isSymbolicLink(dir.toPath())) {
            // ignore symbolic links
            log.debug("Directory {} is a symbolic link. Ignoring.", dir)
            return@mapNotNull null
          }
          log.debug("Directory {} is not a symbolic link.", dir)

          val java = File(dir, "bin/java")
          log.debug("java: {}", java)
          if (!canExecute(java)) {
            // java binary does not exist or is not executable
            log.debug("Java binary {} does not exist or is not executable. canExecute(java): {}", java, canExecute(java))
            return@mapNotNull null
          }
          log.debug("Java binary {} exists and is executable.", java)

          val dist = getDistFromJavaBin(java)
          log.debug("Got JdkDistribution for java binary {}: {}", java, dist)
          return@mapNotNull dist
        } ?: run {
          log.error("Failed to list files in {}", optDir)
          log.debug("optDir.listFiles() returned null.")
          emptyList()
        }
      }
    } catch (e: Exception) {
      log.error("Failed to list java alternatives", e)
      log.debug("Exception caught while finding Java installations: {}", e.message)
      emptyList()
    }.also {
      log.debug("Finished finding Java installations. Result: {}", it)
    }
  }

  private fun canExecute(file: File): Boolean {
    return file.exists() && file.isFile && file.canExecute()
  }

  /**
   * Returns a [JdkDistribution] instances representing the JDK installation of the given
   * `java` binary executable. This binary file is executed to extract the actual `java.home`
   * value.
   *
   * @param java The path to the `java` binary executable.
   * @return The [JdkDistribution] instance, or `null` if there was an error while getting required
   * information from the installation.
   */
  @JvmStatic
  fun getDistFromJavaBin(java: File): JdkDistribution? {
    if (!java.exists() || !java.isFile || !java.canExecute()) {
      log.error(
        "Failed to lookup JDK installation. File '{}' does not exist or cannot be executed.", java)
      return null
    }

    val properties = readProperties(java) ?: run {
      log.error("Failed to retrieve Java properties from java binary: '{}'", java)
      return null
    }

    return readDistFromProps(properties)
  }

  @VisibleForTesting
  internal fun readDistFromProps(properties: String): JdkDistribution? {
    val javaHome = Regex("java\\.home\\s*=\\s*(.*)").find(properties)?.groupValues?.get(1) ?: run {
      log.error("Failed to determine property 'java.home'. Properties: {}", properties)
      return null
    }

    log.debug("Found java.home=${javaHome}")

    val javaVersion = Regex("java\\.version\\s*=\\s*(.*)").find(properties)?.groupValues?.get(1)
      ?: run {
        log.error("Failed to determine property 'java.version'. Properties: {}", properties)
        return null
      }

    log.debug("Found java.version={}", javaVersion)

    return JdkDistribution(javaVersion, javaHome)
  }

  /**
   * Returns a [JdkDistribution] instance representing the JDK installation at the given
   * location.
   *
   * @param javaHome The path to the installed JDK.
   * @return The [JdkDistribution] instance, or `null` if there was an error while getting required
   * information from the installation.
   */
  @JvmStatic
  fun getDistFromJavaHome(javaHome: File): JdkDistribution? {
    return getDistFromJavaBin(javaHome.resolve("bin/java"))
  }

  private fun readProperties(file: File): String? {
    val propsCmd = "${file.absolutePath} -XshowSettings:properties -version"
    val process = executeWithBash(propsCmd) ?: return null
    return process.inputStream.bufferedReader().readText()
  }

  @WorkerThread
  private fun executeWithBash(cmd: String): Process? {
    val shell = Environment.BASH_SHELL

    if (!canExecute(shell)) {
      log.warn(
        "Unable to determine JDK installations. Command {} not found or is not executable.",
        shell.absolutePath)
      return null
    }

    val env = HashMap(TermuxShellEnvironment().getEnvironment(IDEApplication.instance, false))

    return executeProcessAsync {
      command = listOf(shell.absolutePath, "-c", cmd)
      environment = env
      redirectErrorStream = true
      workingDirectory = Environment.HOME
    }
  }
}