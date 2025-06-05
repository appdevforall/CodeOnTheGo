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
import org.adfa.constants.DESTINATION_TERMUX_PACKAGES_FOLDER_NAME
import org.adfa.constants.LOCAL_SOURCE_TERMUX_VAR_FOLDER_NAME
import org.adfa.constants.MANIFEST_FILE_NAME
import org.adfa.constants.SOURCE_LIB_FOLDER
import org.adfa.constants.SPLIT_ASSETS
import com.google.common.io.Files
import com.itsaky.androidide.plugins.util.FolderCopyUtils.Companion.copy
import com.itsaky.androidide.plugins.util.FolderCopyUtils.Companion.copyFolderWithInnerFolders
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.io.IOException
import kotlin.io.path.Path


abstract class CopyTermuxCacheAbiTask : DefaultTask() {

    @get:Input
//    abstract val srcDir: Property<String>
    var srcDir: String = ""

//    @get:Input
//    var srcDir: String = ""

    /**
     * The output directory.
     */
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun copyTermuxAbiCacheToAssets() {
        val outputDirectory = this.outputDirectory.get()
            .file(ASSETS_COMMON_FOLDER + File.separator + LOCAL_SOURCE_TERMUX_LIB_FOLDER_NAME +
                    File.separator + DESTINATION_TERMUX_PACKAGES_FOLDER_NAME).asFile
        val sourceFilePath =
            this.project.projectDir.parentFile.parentFile.path + File.separator + SOURCE_LIB_FOLDER + File.separator +
                    LOCAL_SOURCE_TERMUX_LIB_FOLDER_NAME + File.separator + this.srcDir

        if (!outputDirectory.exists()) {
            outputDirectory.mkdirs()
        }

        if (!File(sourceFilePath).exists()) {
            return
        }

        if (!SPLIT_ASSETS) {
            copy(sourceFilePath, outputDirectory)
        }
    }

}