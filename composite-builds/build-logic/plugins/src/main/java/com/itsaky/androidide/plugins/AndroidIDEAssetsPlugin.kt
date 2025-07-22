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

import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.Variant
import com.itsaky.androidide.build.config.BuildConfig
import com.itsaky.androidide.build.config.downloadVersion
import com.itsaky.androidide.plugins.tasks.AddBrotliFileToAssetsTask
import com.itsaky.androidide.plugins.tasks.AddFileToAssetsTask
import com.itsaky.androidide.plugins.tasks.GenerateInitScriptTask
import com.itsaky.androidide.plugins.tasks.GradleWrapperGeneratorTask
import com.itsaky.androidide.plugins.tasks.SetupAapt2Task
import com.itsaky.androidide.plugins.util.capitalized
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.StopExecutionException
import org.gradle.api.tasks.bundling.Jar

/**
 * Handles asset copying and generation.
 *
 * @author Akash Yadav
 */
class AndroidIDEAssetsPlugin : Plugin<Project> {

    override fun apply(target: Project) {
        target.run {
            val wrapperGeneratorTaskProvider = tasks.register(
                "generateGradleWrapper",
                GradleWrapperGeneratorTask::class.java
            )

            val setupAapt2TaskTaskProvider =
                tasks.register(
                    "setupAapt2",
                    SetupAapt2Task::class.java
                )

            val androidComponentsExtension = extensions.getByType(ApplicationAndroidComponentsExtension::class.java)

            androidComponentsExtension.onVariants { variant ->
                val variantNameCapitalized = variant.name.capitalized()
                variant.sources.jniLibs?.addGeneratedSourceDirectory(
                    setupAapt2TaskTaskProvider, SetupAapt2Task::outputDirectory
                )

                variant.sources.assets?.addGeneratedSourceDirectory(
                    wrapperGeneratorTaskProvider, GradleWrapperGeneratorTask::outputDirectory
                )

                // Add android-init.gradle to assets
                registerInitScriptGeneratorTask(variant, variantNameCapitalized)

                // Add tooling-api-aal.jar
                registerToolingApiJarCopierTask(variant, variantNameCapitalized)

                // Add libjdwp-remote.aar
                registerLibjdwpRemoteAarCopierTask(variant, variantNameCapitalized)

                // Add cogo-plugin.jar
                registerCoGoPluginApiJarCopierTask(variant, variantNameCapitalized)
            }
        }
    }

    private fun Project.registerInitScriptGeneratorTask(
        variant: Variant,
        variantName: String,
    ) {
        val generateInitScript = tasks.register(
            "generate${variantName}InitScript", GenerateInitScriptTask::class.java
        ) {
            mavenGroupId.set(BuildConfig.packageName)
            downloadVersion.set(this@registerInitScriptGeneratorTask.downloadVersion)
        }

        variant.sources.assets?.addGeneratedSourceDirectory(
            generateInitScript, GenerateInitScriptTask::outputDir
        )
    }

    private fun Project.registerToolingApiJarCopierTask(
        variant: Variant,
        variantName: String,
    ) {
        val copyToolingApiJar = tasks.register(
            "copy${variantName}ToolingApiJar",
            if (variant.debuggable) AddFileToAssetsTask::class.java else AddBrotliFileToAssetsTask::class.java
        ) {
            val toolingApi = rootProject.findProject(":subprojects:tooling-api-impl")!!
            val toolingApiJar = toolingApi.layout.buildDirectory.file("libs/tooling-api-all.jar")

            dependsOn(toolingApi.tasks.getByName("copyJar"))

            inputFile.set(toolingApiJar)
            baseAssetsPath.set("data/common")
        }

        variant.sources.assets?.addGeneratedSourceDirectory(
            copyToolingApiJar, AddFileToAssetsTask::outputDirectory
        )
    }

    private fun Project.registerLibjdwpRemoteAarCopierTask(
        variant: Variant,
        variantName: String,
    ) {
        val copyLibJdwpAar = tasks.register(
            "copy${variantName}LibJdwpAar", AddFileToAssetsTask::class.java
        ) {
            val flavor = variant.flavorName!!
            val libjdwpRemote = rootProject.findProject(":subprojects:libjdwp-remote")!!
            dependsOn(libjdwpRemote.tasks.getByName("assemble${flavor.capitalized()}Release"))

            val libjdwpRemoteAar =
                libjdwpRemote.layout.buildDirectory.file("outputs/aar/libjdwp-remote-$flavor-release.aar")

            inputFile.set(libjdwpRemoteAar)
            baseAssetsPath.set("data/common")
        }

        variant.sources.assets?.addGeneratedSourceDirectory(
            copyLibJdwpAar, AddFileToAssetsTask::outputDirectory
        )
    }

    private fun Project.registerCoGoPluginApiJarCopierTask(
        variant: Variant,
        variantName: String,
    ) {
        val copyCogoPluginJar = tasks.register(
            "copy${variantName}CogoPluginJar", AddFileToAssetsTask::class.java
        ) {

            val cogoPluginApi = rootProject.findProject(":gradle-plugin")
                ?: throw IllegalStateException("Required project ':gradle-plugin' not found.")

            val jarTaskProvider = cogoPluginApi.tasks.named("jar", Jar::class.java)

            // Depend on the task provider. Gradle ensures it runs.
            dependsOn(jarTaskProvider)

            inputFile.set(jarTaskProvider.flatMap { it.archiveFile })
            baseAssetsPath.set("data/common")
        }

        variant.sources.assets?.addGeneratedSourceDirectory(
            copyCogoPluginJar, AddFileToAssetsTask::outputDirectory
        )
    }
}

