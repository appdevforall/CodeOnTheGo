package com.itsaky.androidide.plugins.build

import org.gradle.api.Plugin
import org.gradle.api.Project
import java.io.File

class PluginBuilder : Plugin<Project> {

    override fun apply(target: Project) {
        val extension = target.extensions.create(
            "pluginBuilder",
            PluginBuilderExtension::class.java
        )

        target.afterEvaluate {
            createDebugTask(target, extension)
            createReleaseTask(target, extension)
        }
    }

    private fun createDebugTask(project: Project, extension: PluginBuilderExtension) {
        val task = project.tasks.create("assemblePluginDebug")
        task.group = "build"
        task.description = "Assembles the debug plugin and creates .cgp file"
        task.dependsOn("assembleDebug")

        task.doLast(object : org.gradle.api.Action<org.gradle.api.Task> {
            override fun execute(t: org.gradle.api.Task) {
                val pluginName = extension.pluginName.getOrElse(project.name)
                val apkDir = File(project.buildDir, "outputs/apk/debug")
                val outputDir = File(project.buildDir, "plugin")
                outputDir.mkdirs()

                project.logger.lifecycle("Looking for APK in: ${apkDir.absolutePath}")

                val apkFile = apkDir.listFiles()?.firstOrNull { it.extension == "apk" }
                if (apkFile == null) {
                    project.logger.warn("No APK found in ${apkDir.absolutePath}")
                    return
                }

                val outputFile = File(outputDir, "$pluginName-debug.cgp")
                apkFile.copyTo(outputFile, overwrite = true)
                apkFile.delete()
                project.logger.lifecycle("Plugin assembled: ${outputFile.absolutePath}")
            }
        })
    }

    private fun createReleaseTask(project: Project, extension: PluginBuilderExtension) {
        val task = project.tasks.create("assemblePlugin")
        task.group = "build"
        task.description = "Assembles the release plugin and creates .cgp file"
        task.dependsOn("assembleRelease")

        task.doLast(object : org.gradle.api.Action<org.gradle.api.Task> {
            override fun execute(t: org.gradle.api.Task) {
                val pluginName = extension.pluginName.getOrElse(project.name)
                val apkDir = File(project.buildDir, "outputs/apk/release")
                val outputDir = File(project.buildDir, "plugin")
                outputDir.mkdirs()

                project.logger.lifecycle("Looking for APK in: ${apkDir.absolutePath}")

                val apkFile = apkDir.listFiles()?.firstOrNull { it.extension == "apk" }
                if (apkFile == null) {
                    project.logger.warn("No APK found in ${apkDir.absolutePath}")
                    return
                }

                val outputFile = File(outputDir, "$pluginName.cgp")
                apkFile.copyTo(outputFile, overwrite = true)
                apkFile.delete()
                project.logger.lifecycle("Plugin assembled: ${outputFile.absolutePath}")
            }
        })
    }
}