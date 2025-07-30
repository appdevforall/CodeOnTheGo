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
import com.itsaky.androidide.plugins.conf.hasBundledAssets
import com.itsaky.androidide.plugins.tasks.AddBrotliFileToAssetsTask
import com.itsaky.androidide.plugins.tasks.AddFileToAssetsTask
import com.itsaky.androidide.plugins.tasks.GenerateInitScriptTask
import com.itsaky.androidide.plugins.tasks.GradleWrapperGeneratorTask
import com.itsaky.androidide.plugins.util.capitalized
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.bundling.Jar
import org.gradle.kotlin.dsl.register

/**
 * Handles asset copying and generation.
 *
 * @author Akash Yadav
 */
class AndroidIDEAssetsPlugin : Plugin<Project> {

    override fun apply(target: Project) {
        target.run {
            val wrapperGeneratorTaskProvider = tasks.register(
                "generateGradleWrapper", GradleWrapperGeneratorTask::class.java
            )

            val androidComponentsExtension =
                extensions.getByType(ApplicationAndroidComponentsExtension::class.java)

            androidComponentsExtension.onVariants { variant ->
                val variantNameCapitalized = variant.name.capitalized()
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

                // Add logsender.aar
                registerLogSenderAarCopierTask(variant, variantNameCapitalized)
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
        val taskName = "copy${variantName}ToolingApiJar"
        val projectPath = ":subprojects:tooling-api-impl"
        val projectTask = "copyJar"
        val inputFile: (Project) -> Provider<RegularFile> =
            { project -> project.layout.buildDirectory.file("libs/tooling-api-all.jar") }

        if (hasBundledAssets(variant)) {
            addProjectArtifactToAssets<AddBrotliFileToAssetsTask>(
                variant = variant,
                taskName = taskName,
                projectPath = projectPath,
                projectTask = projectTask,
                onGetInputFile = inputFile
            )
        } else {
            addProjectArtifactToAssets<AddFileToAssetsTask>(
                variant = variant,
                taskName = taskName,
                projectPath = projectPath,
                projectTask = projectTask,
                onGetInputFile = inputFile
            )
        }
    }

    private fun Project.registerLibjdwpRemoteAarCopierTask(
        variant: Variant,
        variantName: String,
    ) {
        val flavorName = variant.flavorName!!
        addProjectArtifactToAssets<AddFileToAssetsTask>(
            variant = variant,
            taskName = "copy${variantName}LibJdwpRemoteAar",
            projectPath = ":subprojects:libjdwp-remote",
            projectTask = "assemble${flavorName.capitalized()}Release",
        ) { project ->
            project.layout.buildDirectory.file("outputs/aar/libjdwp-remote-${flavorName}-release.aar")
        }
    }

    private fun Project.registerCoGoPluginApiJarCopierTask(
        variant: Variant,
        variantName: String,
    ) {
        evaluationDependsOn(":gradle-plugin")
        addProjectArtifactToAssets<AddFileToAssetsTask>(
            variant = variant,
            taskName = "copy${variantName}CogoPluginJar",
            projectPath = ":gradle-plugin",
            projectTask = "jar",
        ) { project ->
            project.tasks.named("jar", Jar::class.java).flatMap { it.archiveFile }
        }
    }

    private fun Project.registerLogSenderAarCopierTask(
        variant: Variant, variantName: String
    ) {
        val flavorName = variant.flavorName!!
        addProjectArtifactToAssets<AddFileToAssetsTask>(
            variant = variant,
            taskName = "copy${variantName}LogSenderAar",
            projectPath = ":logsender",
            projectTask = "assemble${flavorName.capitalized()}Release",
        ) { project ->
            project.layout.buildDirectory.file("outputs/aar/logsender-${flavorName}-release.aar")
        }
    }

    private inline fun <reified T : AddFileToAssetsTask> Project.addProjectArtifactToAssets(
        variant: Variant,
        taskName: String,
        projectPath: String,
        projectTask: String,
        baseAssetsPath: String = "data/common",
        crossinline onGetInputFile: (Project) -> Provider<RegularFile>,
    ) {
        val copyArtifactTask = tasks.register<T>(taskName) {
            val project = project.rootProject.findProject(projectPath)
                ?: throw GradleException("Project with path '${projectPath}' not found")

            val task = project.tasks.getByName(projectTask)
            dependsOn(task)

            val inputFile = onGetInputFile(project)
            this.inputFile.set(inputFile)
            this.baseAssetsPath.set(baseAssetsPath)
        }

        variant.sources.assets?.addGeneratedSourceDirectory(
            copyArtifactTask, AddFileToAssetsTask::outputDirectory
        )
    }
}

