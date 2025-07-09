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

package com.itsaky.androidide.plugins

import com.itsaky.androidide.plugins.util.DownloadUtils
import org.adfa.constants.BOOTSTRAP_SOURCE_FOLDER
import org.adfa.constants.SOURCE_LIB_FOLDER
import com.itsaky.androidide.plugins.util.FolderCopyUtils
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.internal.os.OperatingSystem
import java.io.File
import java.net.URI

/**
 * Gradle plugin which downloads the bootstrap packages for the terminal.
 *
 * @author Akash Yadav
 */
class TerminalBootstrapPackagesPlugin : Plugin<Project> {

  companion object {

    /**
     * The bootstrap packages, mapped with the CPU ABI as the key and the ZIP file's sha256sum as the value.
     */
    private val BOOTSTRAP_PACKAGES = mapOf(
      "aarch64" to "9e247440678443348d4a63c6d693e430198c27a7fc88ca7a3ef8bc1898076e82",
      "arm" to "e82d87072c9d5106f1d68ee8bc6c6720551c5e8f4b7aa2f14b43e964b271a601",
    )

    /**
     * The bootstrap packages version, basically the tag name of the GitHub release.
     */
    private const val BOOTSTRAP_PACKAGES_VERSION = "2025.07.09"
    private const val PACKAGES_DOWNLOAD_URL = "https://github.com/appdevforall/terminal-packages/releases/download/%1\$s/bootstrap-%2\$s.9.zip"
  }

  override fun apply(target: Project) {
    target.run {

      val bootstrapOut = project.layout.buildDirectory.file("intermediates/bootstrap-packages")
        .get().asFile

      val files = BOOTSTRAP_PACKAGES.map { (arch, sha256) ->
        val file = File(bootstrapOut, "bootstrap-${arch}.zip")
        file.parentFile.mkdirs()

        DownloadUtils.downloadFile(
          logger = logger,
          url = URI.create(PACKAGES_DOWNLOAD_URL.format(BOOTSTRAP_PACKAGES_VERSION, arch)).toURL(),
          sha256Checksum = sha256,
          destination = file
        )

        return@map arch to file
      }.toMap()

      // TODO: Remove the __x86_64__ definition and below, the test for it. --DS, 18-May-2025
      project.file("src/main/cpp/termux-bootstrap-zip.S").writeText(
        """
             .global blob
             .global blob_size
             .section .rodata
         blob:
        #if defined __aarch64__
             .incbin "${escapePathOnWindows(files["aarch64"]!!.absolutePath)}"
         #elif defined __arm__
             .incbin "${escapePathOnWindows(files["arm"]!!.absolutePath)}"
         #elif defined __x86_64__

         #else
         # error Unsupported arch
         #endif
         1:
         blob_size:
             .int 1b - blob
         
      """.trimIndent()
      )
    }
  }

  private fun escapePathOnWindows(path: String): String {
    if (OperatingSystem.current().isWindows) {
      // escape backslashes when building on Windows
      return path.replace("\\", "\\\\")
    }

    return path
  }
}
