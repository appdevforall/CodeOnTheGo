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

package com.itsaky.androidide.plugins.tasks

import com.adfa.constants.ASSETS_COMMON_FOLDER
import com.adfa.constants.LOACL_GRADLE_8_0_0_CACHES_PATH
import com.adfa.constants.LOACL_SOURCE_AGP_8_0_0_CACHES_DEST
import com.adfa.constants.LOCAL_TERMUX_LIB_FOLDER_PATH
import com.adfa.constants.SOURCE_LIB_FOLDER
import com.itsaky.androidide.plugins.util.FolderCopyUtils.Companion.copyFolderWithInnerFolders
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.io.path.Path

abstract class CopyGradleCachesToAssetsTask : DefaultTask() {

    /**
     * The output directory.
     */
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun copyGradleCachesToAssets() {
        val outputDirectory = this.outputDirectory.get().file(ASSETS_COMMON_FOLDER + File.separator + LOACL_SOURCE_AGP_8_0_0_CACHES_DEST).asFile
        if (!outputDirectory.exists()) {
            outputDirectory.mkdirs()
        }

        /**
         * Currently we are hardcoded to LOACL_SOURCE_AGP_8_0_0_CACHES, but we can add
         * an if statement that will change this based on whatever gradle version we choose
         * from the supported once.
         * Supported gradle versions are limited by the pregenerated cahces we have in libs_source
         * folder.
         */
        val sourceFilePath =
            this.project.projectDir.parentFile.path + File.separator + SOURCE_LIB_FOLDER + File.separator + LOACL_GRADLE_8_0_0_CACHES_PATH

        try {
            copyFolderWithInnerFolders(Path(sourceFilePath), Path(outputDirectory.path))
        } catch (e: IOException) {
            e.message?.let { throw GradleException(it) }
        }

    }

}