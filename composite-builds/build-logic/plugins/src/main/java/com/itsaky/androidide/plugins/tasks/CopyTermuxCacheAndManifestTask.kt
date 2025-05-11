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

import org.adfa.constants.ASSETS_COMMON_FOLDER
import org.adfa.constants.LOCAL_SOURCE_TERMUX_LIB_FOLDER_NAME
import org.adfa.constants.MANIFEST_FILE_NAME
import org.adfa.constants.SOURCE_LIB_FOLDER
import com.google.common.io.Files
import com.itsaky.androidide.plugins.util.FolderCopyUtils.Companion.copy
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File


abstract class CopyTermuxCacheAndManifestTask : DefaultTask() {

    /**
     * The output directory.
     */
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun copyTermuxCachesToAssets() {
        val outputDirectory = this.outputDirectory.get()
            .file(ASSETS_COMMON_FOLDER + File.separator + LOCAL_SOURCE_TERMUX_LIB_FOLDER_NAME).asFile
        val sourceFilePath =
            this.project.projectDir.parentFile.path + File.separator + SOURCE_LIB_FOLDER + File.separator + LOCAL_SOURCE_TERMUX_LIB_FOLDER_NAME

        if (!outputDirectory.exists()) {
            outputDirectory.mkdirs()
        }

        // NOTE: disable copying of the full termux lib for now
        // copy(sourceFilePath, outputDirectory)



        val manifestOutputDirectory = this.outputDirectory.get()
            .file(ASSETS_COMMON_FOLDER).asFile.resolve(MANIFEST_FILE_NAME)
        val manifestSourceFilePath =
            this.project.projectDir.parentFile.path + File.separator + SOURCE_LIB_FOLDER + File.separator + MANIFEST_FILE_NAME
        Files.copy(File(manifestSourceFilePath), manifestOutputDirectory)
    }

}