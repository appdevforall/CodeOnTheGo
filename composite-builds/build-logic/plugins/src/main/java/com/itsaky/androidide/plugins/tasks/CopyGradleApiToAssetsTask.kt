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

import org.adfa.constants.DATABASE_FOLDER
import org.adfa.constants.GRADLE_API_NAME_BR
import org.adfa.constants.SOURCE_LIB_FOLDER
import com.google.common.io.Files
import org.adfa.constants.ASSETS_COMMON_FOLDER
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.io.IOException

abstract class CopyGradleApiToAssetsTask : DefaultTask() {

    /**
     * The output directory.
     */
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun CopyGradleApiToAssets() {
        val outputDirectory = this.outputDirectory.get().file(ASSETS_COMMON_FOLDER).asFile
        val sourceFilePath =
            this.project.projectDir.parentFile.path + File.separator + SOURCE_LIB_FOLDER + File.separator + GRADLE_API_NAME_BR
        copy(sourceFilePath, outputDirectory)
    }

    private fun copy(sourceFilePath: String, outputDirectory: File) {
        if (!outputDirectory.exists()) {
            outputDirectory.mkdirs()
        }

        val destFile = outputDirectory.resolve(GRADLE_API_NAME_BR)
        if (destFile.exists()) {
            destFile.delete()
        }

        try {
            Files.copy(File(sourceFilePath), destFile)
        } catch (e: IOException) {
            e.message?.let { throw GradleException(it) }
        }
    }

}