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

package com.itsaky.androidide.templates.base

import com.adfa.constants.LOCAL_ANDROID_GRADLE_PLUGIN_NAME
import com.adfa.constants.APG_SOURCE_FOLDER_NAME
import com.adfa.constants.ASSETS_COMMON_FOLDER
import com.adfa.constants.GRADLE_FOLDER_NAME
import com.adfa.constants.GRADLE_WRAPPER_FILE_NAME
import com.adfa.constants.GRADLE_WRAPPER_PATH_SUFFIX
import com.adfa.constants.LOACL_SOURCE_AGP_8_0_0_CACHES
import com.adfa.constants.LOCAL_ANDROID_GRADLE_PLUGIN_JAR_NAME
import com.blankj.utilcode.util.ResourceUtils
import com.itsaky.androidide.managers.ToolsManager
import com.itsaky.androidide.templates.ModuleTemplate
import com.itsaky.androidide.templates.ModuleTemplateData
import com.itsaky.androidide.templates.ProjectTemplate
import com.itsaky.androidide.templates.ProjectTemplateData
import com.itsaky.androidide.templates.ProjectTemplateRecipeResult
import com.itsaky.androidide.templates.base.root.buildGradleSrcGroovy
import com.itsaky.androidide.templates.base.root.buildGradleSrcKts
import com.itsaky.androidide.templates.base.root.gradleWrapperProps
import com.itsaky.androidide.templates.base.root.settingsGradleSrcStr
import com.itsaky.androidide.templates.base.util.optonallyKts
import com.itsaky.androidide.utils.transferToStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlin.io.path.Path
import kotlin.io.path.pathString

/**
 * Builder for building project templates.
 *
 * @author Akash Yadav
 */
class ProjectTemplateBuilder :
    ExecutorDataTemplateBuilder<ProjectTemplateRecipeResult, ProjectTemplateData>() {

    private var _defModule: ModuleTemplateData? = null

    @PublishedApi
    internal val defModuleTemplate: ModuleTemplate? = null

    @PublishedApi
    internal val modules = mutableListOf<ModuleTemplate>()

    @PublishedApi
    internal val defModule: ModuleTemplateData
        get() = checkNotNull(_defModule) { "Module template data not set" }

    /**
     * Set the template data that will be used to create the default application module in the project.
     *
     * @param data The module template data to use.
     */
    fun setDefaultModuleData(data: ModuleTemplateData) {
        _defModule = data
    }

    /**
     * Get the asset path for base root project template.
     *
     * @param path The path to the asset.
     * @see com.itsaky.androidide.templates.base.baseAsset
     */
    fun baseAsset(path: String) =
        com.itsaky.androidide.templates.base.util.baseAsset("root", path)

    /**
     * Get the `build.gradle[.kts] file for the project.
     */
    fun buildGradleFile(): File {
        return data.buildGradleFile()
    }

    /**
     * Writes the `build.gradle[.kts]` file in the project root directory.
     */
    fun buildGradle() {
        executor.save(buildGradleSrc(), buildGradleFile())
    }

    /**
     * Get the source for `build.gradle[.kts]` files.
     */
    fun buildGradleSrc(): String {
        return if (data.useKts) buildGradleSrcKts() else buildGradleSrcGroovy()
    }

    /**
     * Writes the `settings.gradle[.kts]` file in the project root directory.
     */
    fun settingsGradle() {
        executor.save(settingsGradleSrc(), settingsGradleFile())
    }

    /**
     * Get the `settings.gradle[.kts]` file for this project.
     */
    fun settingsGradleFile(): File {
        return File(data.projectDir, data.optonallyKts("settings.gradle"))
    }

    /**
     * Get the source for `settings.gradle[.kts]`.
     */
    fun settingsGradleSrc(): String {
        return settingsGradleSrcStr()
    }

    /**
     * Writes the `gradle.properties` file in the root project.
     */
    fun gradleProps() {
        val name = "gradle.properties"
        val gradleProps = File(data.projectDir, name)
        executor.copyAsset(baseAsset(name), gradleProps)
    }

    /**
     * Writes/copies the Gradle Wrapper related files in the project directory.
     *
     * This method created gradle folder, child folders and copies gradleWrapper jar and
     * gradle-wrapper.properties files.
     * So anything that wshould be put under gradle folder should be called after this.
     * We can change this behaviour by separating folder creation from file creation.
     * But I will leave it as is for now.
     *
     */
    fun gradleWrapper() {

        ZipInputStream(
            executor.openAsset(ToolsManager.getCommonAsset("gradle-wrapper.zip")).buffered()
        ).use { zipIn ->
            val entriesToCopy =
                arrayOf("gradlew", "gradlew.bat", "gradle/wrapper/gradle-wrapper.jar")

            var zipEntry: ZipEntry? = zipIn.nextEntry
            while (zipEntry != null) {
                if (zipEntry.name in entriesToCopy) {
                    val fileOut = File(data.projectDir, zipEntry.name)
                    fileOut.parentFile!!.mkdirs()

                    fileOut.outputStream().buffered().use { outStream ->
                        zipIn.transferToStream(outStream)
                        outStream.flush()
                    }
                }

                zipEntry = zipIn.nextEntry
            }


            val gradlew = File(data.projectDir, "gradlew")
            val gradlewBat = File(data.projectDir, "${gradlew.name}.bat")

            check(gradlew.exists()) {
                "'$gradlew' does not exist!"
            }
            check(gradlewBat.exists()) {
                "'$gradlew' does not exist!"
            }

            gradlew.setExecutable(true)
            gradlewBat.setExecutable(true)
        }
        gradleWrapperProps()
    }

    /**
     * Writes the `.gitignore` file in the project directory.
     */
    fun gitignore() {
        val gitignore = File(data.projectDir, ".gitignore")
        executor.copyAsset(baseAsset("gitignore"), gitignore)
    }

    /**
     * Copies local gradle version from androidIDE to gradle folder inside the created project.
     */
    fun gradleZip(gradleFileName: String = GRADLE_WRAPPER_FILE_NAME) {
        val result = ResourceUtils.copyFileFromAssets(
            File(ToolsManager.getCommonAsset(gradleFileName)).path,
            File(data.projectDir.absolutePath + File.separator + GRADLE_WRAPPER_PATH_SUFFIX + gradleFileName).path
        )
        if (!result) {
            println("Gradle files copy failed + ${this.javaClass}")
        }

    }

    fun agpJar(agpFileName: String = LOCAL_ANDROID_GRADLE_PLUGIN_JAR_NAME) {
        val result = ResourceUtils.copyFileFromAssets(
            File(ToolsManager.getCommonAsset(agpFileName)).path,
            File(data.projectDir.absolutePath + File.separator + GRADLE_FOLDER_NAME + File.separator + agpFileName).path
        )
        if (!result) {
            println("Android Gradle files copy failed + ${this.javaClass}")
        }
    }

    fun gradleCaches() {
        executor.updateCaches()
    }

    override fun buildInternal(): ProjectTemplate {
        return ProjectTemplate(
            modules, templateName!!, thumb!!,
            widgets!!, recipe!!
        )
    }
}
