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

import android.content.Context
import com.adfa.constants.DEST_GRADLE_FOLDER_NAME
import com.adfa.constants.GRADLE_ZIP_FILE_NAME
import com.adfa.constants.LOACL_AGP_8_0_0_CACHES_DEST
import com.blankj.utilcode.util.ResourceUtils
import com.blankj.utilcode.util.ZipUtils
import com.itsaky.androidide.app.IDEApplication
import com.itsaky.androidide.managers.ToolsManager
import com.itsaky.androidide.templates.RecipeExecutor
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * [RecipeExecutor] implementation used for creating projects.
 *
 * @author Akash Yadav
 */
class TemplateRecipeExecutor : RecipeExecutor {

    private val application: IDEApplication
        get() = IDEApplication.instance

    override fun copy(source: File, dest: File) {
        source.copyTo(dest)
    }

    override fun save(source: String, dest: File) {
        dest.parentFile?.mkdirs()
        dest.writeText(source)
    }

    override fun openAsset(path: String): InputStream {
        try {
            return application.assets.open(path)
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
    }

    override fun copyAsset(path: String, dest: File) {
        openAsset(path).use {
            it.copyTo(dest.outputStream())
        }
    }

    override fun copyAssetsRecursively(path: String, destDir: File) {
        ResourceUtils.copyFileFromAssets(path, destDir.absolutePath)
    }

    override fun updateCaches(gradlePath: String) {
        val outputDirectory =
            File(application.filesDir.path + File.separator + LOACL_AGP_8_0_0_CACHES_DEST)
        val zipFile = File("$outputDirectory${File.separator}$GRADLE_ZIP_FILE_NAME")
        if (!outputDirectory.exists()) {
            outputDirectory.mkdirs()
        }

        try {
            ResourceUtils.copyFileFromAssets(
                ToolsManager.getCommonAsset(gradlePath),
                outputDirectory.path
            )
            ZipUtils.unzipFile(
                zipFile,
                outputDirectory
            )
            zipFile.delete()
        } catch (e: IOException) {
            println("Android Gradle caches copy failed + ${e.message}")
        }
    }

}