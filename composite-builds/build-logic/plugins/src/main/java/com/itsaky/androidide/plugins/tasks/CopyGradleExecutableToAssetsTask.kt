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
import com.adfa.constants.GRADLE_WRAPPER_FILE_NAME
import com.adfa.constants.SOURCE_LIB_FOLDER
import com.adfa.constants.Sdk
import com.google.common.io.Files
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.io.IOException

abstract class CopyGradleExecutableToAssetsTask : DefaultTask() {

    /**
     * The output directory.
     */
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun copyGradleExecutableToAssets() {
        val outputDirectory = this.outputDirectory.get().file(ASSETS_COMMON_FOLDER).asFile
        if (!outputDirectory.exists()) {
            outputDirectory.mkdirs()
        }

        val destFile = outputDirectory.resolve(GRADLE_WRAPPER_FILE_NAME)

        if (destFile.exists()) {
            destFile.delete()
        }

        val sourceFilePath =
            this.project.projectDir.parentFile.path + File.separator + SOURCE_LIB_FOLDER + File.separator + GRADLE_WRAPPER_FILE_NAME

        try {
            Files.copy(File(sourceFilePath), destFile)
        } catch (e: IOException) {
            e.message?.let { throw GradleException(it) }
        }

    }
}