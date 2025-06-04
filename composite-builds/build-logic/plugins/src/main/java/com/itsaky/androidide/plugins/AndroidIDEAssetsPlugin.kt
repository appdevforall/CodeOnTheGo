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

import org.adfa.constants.COPY_ANDROID_SDK_TO_ASSETS
import org.adfa.constants.COPY_GRADLE_CACHES_TO_ASSETS
import org.adfa.constants.COPY_GRADLE_EXECUTABLE_TASK_NAME
import org.adfa.constants.COPY_TERMUX_LIBS_TASK_NAME
import org.adfa.constants.COPY_DOC_DB_TO_ASSETS
import org.adfa.constants.SPLIT_ASSETS
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.itsaky.androidide.build.config.BuildConfig
import com.itsaky.androidide.build.config.downloadVersion
import com.itsaky.androidide.plugins.tasks.AddAndroidJarToAssetsTask
import com.itsaky.androidide.plugins.tasks.AddFileToAssetsTask
import com.itsaky.androidide.plugins.tasks.CopyDocDbToAssetsTask
import com.itsaky.androidide.plugins.tasks.CopyGradleCachesToAssetsTask
import com.itsaky.androidide.plugins.tasks.CopyGradleExecutableToAssetsTask
import com.itsaky.androidide.plugins.tasks.CopySdkToAssetsTask
import com.itsaky.androidide.plugins.tasks.CopyTermuxCacheAndManifestTask
import com.itsaky.androidide.plugins.tasks.GenerateInitScriptTask
import com.itsaky.androidide.plugins.tasks.GradleWrapperGeneratorTask
import com.itsaky.androidide.plugins.tasks.SetupAapt2Task
import com.itsaky.androidide.plugins.util.SdkUtils.getAndroidJar
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider
import org.gradle.configurationcache.extensions.capitalized

/**
 * Handles asset copying and generation.
 *
 * @author Akash Yadav
 *
 * Keywors:[build, gradle, copyJar, assets, tooling, data/common, libs]
 * This class create new tasks and generates init script. It also specifies the
 * androidIDE android gradle plugin version.
 * It is also related to copyJar task and tooling system for the app.
 * @see ToolingApiServerImpl
 *
 */
class AndroidIDEAssetsPlugin : Plugin<Project> {

    override fun apply(target: Project) {
        // Ensure :gradle-plugin project is configured before this project proceeds
        target.evaluationDependsOn(":gradle-plugin")
        target.run {
            val wrapperGeneratorTaskProvider = tasks.register(
                "generateGradleWrapper",
                GradleWrapperGeneratorTask::class.java
            )

            val androidComponentsExtension = extensions.getByType(
                ApplicationAndroidComponentsExtension::class.java
            )

            val setupAapt2TaskTaskProvider = tasks.register("setupAapt2", SetupAapt2Task::class.java)

            val gradleExecutableToAssetsTaskProvider = tasks.register(
                COPY_GRADLE_EXECUTABLE_TASK_NAME,
                CopyGradleExecutableToAssetsTask::class.java
            )

            val gradleTermuxLibsToAssetsTaskProvider = tasks.register(COPY_TERMUX_LIBS_TASK_NAME, CopyTermuxCacheAndManifestTask::class.java)

            val gradleCachesToAssetsTaskProvider = tasks.register(
                COPY_GRADLE_CACHES_TO_ASSETS,
                CopyGradleCachesToAssetsTask::class.java
            )

            val androidSdkToAssetsTaskProvider = tasks.register(
                COPY_ANDROID_SDK_TO_ASSETS,
                CopySdkToAssetsTask::class.java
            )

            val docDbToAssetsTaskProvider = tasks.register(
                COPY_DOC_DB_TO_ASSETS,
                CopyDocDbToAssetsTask::class.java
            )

            if (SPLIT_ASSETS) {
                gradleExecutableToAssetsTaskProvider.configure { enabled = false }
                gradleCachesToAssetsTaskProvider.configure { enabled = false }
                androidSdkToAssetsTaskProvider.configure { enabled = false }
                docDbToAssetsTaskProvider.configure { enabled = false }
            }

            androidComponentsExtension.onVariants { variant ->

                val variantNameCapitalized = variant.name.capitalized()

                variant.sources.jniLibs?.addGeneratedSourceDirectory(
                    setupAapt2TaskTaskProvider,
                    SetupAapt2Task::outputDirectory
                )

                variant.sources.assets?.addGeneratedSourceDirectory(
                    wrapperGeneratorTaskProvider,
                    GradleWrapperGeneratorTask::outputDirectory
                )

                // NOTE: skip adding android.jar to apk
                //variant.sources.assets?.addGeneratedSourceDirectory(
                //    addAndroidJarTaskProvider,
                //    AddAndroidJarToAssetsTask::outputDirectory
                //)

                // Init script generator
                val generateInitScript = tasks.register(
                    "generate${variantNameCapitalized}InitScript",
                    GenerateInitScriptTask::class.java
                ) {
                    mavenGroupId.set(BuildConfig.packageName)
                    downloadVersion.set(this@run.downloadVersion)
                }

                variant.sources.assets?.addGeneratedSourceDirectory(
                    generateInitScript,
                    GenerateInitScriptTask::outputDir
                )

                // Tooling API JAR copier
                val copyToolingApiJar = tasks.register(
                    "copy${variantNameCapitalized}ToolingApiJar",
                    AddFileToAssetsTask::class.java
                ) {
                    val toolingApi = rootProject.findProject(":subprojects:tooling-api-impl")!!
                    dependsOn(toolingApi.tasks.getByName("copyJar"))

                    val toolingApiJar = toolingApi.layout.buildDirectory.file("libs/tooling-api-all.jar")

                    inputFile.set(toolingApiJar)
                    baseAssetsPath.set("data/common")
                }

                variant.sources.assets?.addGeneratedSourceDirectory(
                    copyToolingApiJar,
                    AddFileToAssetsTask::outputDirectory
                )

                if (SPLIT_ASSETS) {
                    copyToolingApiJar.configure { enabled = false }
                }

                val copyCogoPluginJar = tasks.register(
                    "copy${variantNameCapitalized}CogoPluginJar",
                    AddFileToAssetsTask::class.java
                ) {

                    val cogopluginApi = rootProject.findProject(":gradle-plugin")
                    if(cogopluginApi == null) {
                        // Fail the build if :gradle-plugin is essential
                        throw IllegalStateException("Required project ':gradle-plugin' not found.")
                    } else {
                        try {
                            val jarTaskProvider = cogopluginApi.tasks.named("jar", org.gradle.api.tasks.bundling.Jar::class.java)

                            // Depend on the task provider. Gradle ensures it runs.
                            dependsOn(jarTaskProvider)

                            inputFile.set(jarTaskProvider.flatMap { it.archiveFile })
                        } catch(e: org.gradle.api.UnknownTaskException) {
                            throw IllegalStateException("Could not find required task '${cogopluginApi.path}:jar'.", e)
                        }

                        baseAssetsPath.set("data/common")
                        this.doFirst {
                            val resolvedInputFile = inputFile.get().asFile
                            if(!resolvedInputFile.exists()) {
                                throw org.gradle.api.tasks.StopExecutionException("Input file ${resolvedInputFile.absolutePath} does not exist at execution time!")
                            }
                        }
                    }
                }

                variant.sources.assets?.addGeneratedSourceDirectory(
                    copyCogoPluginJar,
                    AddFileToAssetsTask::outputDirectory
                )


                // Local gradle zip copier
                variant.sources.assets?.addGeneratedSourceDirectory(
                    gradleExecutableToAssetsTaskProvider,
                    CopyGradleExecutableToAssetsTask::outputDirectory
                )

                // Local termux libs copier
                variant.sources.assets?.addGeneratedSourceDirectory(
                    gradleTermuxLibsToAssetsTaskProvider,
                    CopyTermuxCacheAndManifestTask::outputDirectory
                )

                // Local gradle caches copier
                variant.sources.assets?.addGeneratedSourceDirectory(
                    gradleCachesToAssetsTaskProvider,
                    CopyGradleCachesToAssetsTask::outputDirectory
                )

                // documentation db copier
                variant.sources.assets?.addGeneratedSourceDirectory(
                    androidSdkToAssetsTaskProvider,
                    CopySdkToAssetsTask::outputDirectory
                )

                // Local android sdk version copier
                variant.sources.assets?.addGeneratedSourceDirectory(
                    docDbToAssetsTaskProvider,
                    CopyDocDbToAssetsTask::outputDirectory
                )
            }
        }
    }
}

