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

package com.itsaky.androidide.templates

import android.content.Context
import com.adfa.constants.LOACL_SOURCE_AGP_8_0_0_CACHES
import com.adfa.constants.LOACL_SOURCE_AGP_8_0_0_CACHES
import com.blankj.utilcode.util.ResourceUtils
import com.itsaky.androidide.managers.ToolsManager
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.pathString

/**
 * Handles execution of template recipe.
 *
 * @author Akash Yadav
 */
interface RecipeExecutor {

    /**
     * Get the project template data. This is available only while creating modules in an existing project.
     *
     * @return The project template data or `null` if the not available.
     */
    fun projectData(): ProjectTemplateData? = null

    /**
     * @return The [ProjectTemplateData] if available, throws [IllegalStateException] otherwise.
     * @see projectData
     */
    fun requireProjectData(): ProjectTemplateData = checkNotNull(projectData())

    /**
     * Copy the [source] file to [dest].
     */
    fun copy(source: File, dest: File)

    /**
     * Save the [source] to [dest].
     */
    fun save(source: String, dest: File)

    /**
     * Open the given asset path.
     *
     * @return The [InputStream] for the asset.
     */
    fun openAsset(path: String): InputStream

    /**
     * Copies the asset at the given path to the specified destination.
     *
     * @param path The path of the asset.
     * @param dest The destination path.
     */
    fun copyAsset(path: String, dest: File)

    /**
     * Copies the asset directory path to the specified destination directory.
     *
     * @param path The asset path.
     * @param destDir The destination directory.
     */
    fun copyAssetsRecursively(path: String, destDir: File)

    /**
     * Copies gradle caches to androidIDE
     * /data/data/com.itsaky.androidide/files/home/.gradle/caches/modules-2/files-2.1
     * @param gradlePath is a path to source caches, change it in case you will need different
     * Pay attention that due to the way implementation is made, context that is required by
     * updateCaches method is provided implicitly inside the implementation.
     * set of caches.
     *
     * This appends missing files and does not replace existing files.
     */
    fun updateCaches(gradlePath: String = LOACL_SOURCE_AGP_8_0_0_CACHES)

}