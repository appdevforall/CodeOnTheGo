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

import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.absolute
import kotlin.io.path.exists
import kotlin.io.path.readText

const val PROJECT_ROOT_FILE = ".androidide_root"

/**
 * Provides paths to various directories and files.
 *
 * @author Akash Yadav
 */
class FileProvider {

  companion object {

    private val projectRoot by lazy {
      currentDir().findProjectRoot()?.absolute()?.normalize()
    }

    @JvmField
    val extension = run {
      val file = File(".").canonicalFile
      when (file.name) {
        "xml" -> "xml" // Testing in ':lsp:xml' module
        else -> "java" // Testing in ':lsp:java' or ':lsp:testing' module
      }
    }

    @JvmStatic
    fun currentDir(): Path = Paths.get(System.getProperty("user.dir")!!)

    @JvmStatic
    fun implModule(): Path = projectRoot().resolve("subprojects/tooling-api-impl")

    @JvmStatic
    fun projectRoot(): Path =
      checkNotNull(projectRoot) {
        "Unable to file project root. Check if '${PROJECT_ROOT_FILE}' file exists in the project root directory."
      }

    @JvmStatic
    private fun testingDir() = projectRoot().resolve("testing")

    @JvmStatic
    private fun testResourcesDir(): Path = testingDir().resolve("resources")

    @JvmStatic
    fun testHomeDir(): Path = testResourcesDir().resolve("test-home")

    @JvmStatic
    fun testProjectRoot(): Path = testResourcesDir().resolve("test-project")

    @JvmStatic
    fun sampleProjectRoot(): Path = testResourcesDir().resolve("sample-project")

    /**
     * Get the path to the 'resources' directory.
     *
     * @return The the resources directory.
     */
    @JvmStatic
    fun resources(): Path {
      return testProjectRoot().resolve("app/src/main/resources")
    }

    /**
     * Get the path to the file in resources.
     *
     * @param name The name of the file. Nested file paths can be separated using '/'.
     * @return The path to the file.
     */
    @JvmStatic
    fun sourceFile(name: String): Path {
      return resources().resolve("${name}_template.$extension").normalize()
    }

    @JvmStatic
    fun contents(file: Path): StringBuilder = StringBuilder(file.readText())
  }
}

private fun Path.findProjectRoot(): Path? {
  // Walk UP from `this` looking for the marker in each ancestor. The previous
  // implementation walked the whole subtree at every level, which is both wildly
  // expensive and racy against concurrent Gradle tasks that mutate `build/`
  // (e.g. NoSuchFileException on transient kotlin-classes during parallel tasks).
  if (resolve(PROJECT_ROOT_FILE).exists()) {
    return this
  }
  return parent?.findProjectRoot()
}
