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
import org.adfa.constants.LOCAL_ANDROID_GRADLE_PLUGIN_JAR_NAME
import org.adfa.constants.LOCAL_SOURCE_ANDROID_GRADLE_PLUGIN_VERSION_NAME
import org.adfa.constants.LOCAL_SOURCE_ANDROID_KOTLIN_GRADLE_PLUGIN_VERSION_NAME
import org.adfa.constants.SOURCE_LIB_FOLDER
import com.google.common.io.Files
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.io.IOException

abstract class CopyGradleAndroidExceutableToAssetsTask : DefaultTask() {

    /**
     * The output directory.
     */
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun copyGradleAndroidPluginExecutableToAssets() {
        val outputDirectory = this.outputDirectory.get().file(ASSETS_COMMON_FOLDER).asFile
        if (!outputDirectory.exists()) {
            outputDirectory.mkdirs()
        }
        val destFile = outputDirectory.resolve(LOCAL_ANDROID_GRADLE_PLUGIN_JAR_NAME)

        if (destFile.exists()) {
            destFile.delete()
        }

        val kotlinDestFile = outputDirectory.resolve(
            LOCAL_SOURCE_ANDROID_KOTLIN_GRADLE_PLUGIN_VERSION_NAME
        )

        if (kotlinDestFile.exists()) {
            kotlinDestFile.delete()
        }

        val sourceFilePath =
            this.project.projectDir.parentFile.path + File.separator + SOURCE_LIB_FOLDER + File.separator + LOCAL_SOURCE_ANDROID_GRADLE_PLUGIN_VERSION_NAME
        val kotlinSourceFilePath =
            this.project.projectDir.parentFile.path + File.separator + SOURCE_LIB_FOLDER + File.separator + LOCAL_SOURCE_ANDROID_KOTLIN_GRADLE_PLUGIN_VERSION_NAME

        try {
            Files.copy(File(sourceFilePath), destFile)
            Files.copy(File(kotlinSourceFilePath), kotlinDestFile)
        } catch (e: IOException) {
            e.message?.let { throw GradleException(it) }
        }

    }

}