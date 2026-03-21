package com.itsaky.androidide.plugins.build

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class PluginBuilder : Plugin<Project> {

    companion object {
        private val TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
        private const val DEFAULT_VERSION = "1.0.0"
    }

    override fun apply(target: Project) {
        val extension = target.extensions.create(
            "pluginBuilder",
            PluginBuilderExtension::class.java
        )

        val androidExtension = target.extensions.getByType(ApplicationExtension::class.java)
        val componentsExtension = target.extensions.getByType(
            ApplicationAndroidComponentsExtension::class.java
        )
        componentsExtension.onVariants { variant ->
            val resolvedVersion = if (extension.pluginVersion.isPresent) {
                extension.pluginVersion.get()
            } else {
                val baseVersion = androidExtension.defaultConfig.versionName ?: DEFAULT_VERSION
                val timestamp = LocalDateTime.now().format(TIMESTAMP_FORMATTER)
                "$baseVersion-${variant.name}.$timestamp"
            }
            variant.manifestPlaceholders.put("pluginVersion", resolvedVersion)
            target.logger.lifecycle("PluginBuilder: version resolved to '$resolvedVersion'")
        }

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

        val pluginName = extension.pluginName.getOrElse(project.name)
        val apkDir = File(project.buildDir, "outputs/apk/debug")
        val outputDir = File(project.buildDir, "plugin")

        task.doLast(object : org.gradle.api.Action<org.gradle.api.Task> {
            override fun execute(t: org.gradle.api.Task) {
                outputDir.mkdirs()

                t.logger.lifecycle("Looking for APK in: ${apkDir.absolutePath}")

                val apkFile = apkDir.listFiles()?.firstOrNull { it.extension == "apk" }
                if (apkFile == null) {
                    t.logger.warn("No APK found in ${apkDir.absolutePath}")
                    return
                }

                val outputFile = File(outputDir, "$pluginName-debug.cgp")
                apkFile.copyTo(outputFile, overwrite = true)
                apkFile.delete()
                t.logger.lifecycle("Plugin assembled: ${outputFile.absolutePath}")
            }
        })
    }

    private fun createReleaseTask(project: Project, extension: PluginBuilderExtension) {
        val task = project.tasks.create("assemblePlugin")
        task.group = "build"
        task.description = "Assembles the release plugin and creates .cgp file"
        task.dependsOn("assembleRelease")

        val pluginName = extension.pluginName.getOrElse(project.name)
        val apkDir = File(project.buildDir, "outputs/apk/release")
        val outputDir = File(project.buildDir, "plugin")

        task.doLast(object : org.gradle.api.Action<org.gradle.api.Task> {
            override fun execute(t: org.gradle.api.Task) {
                outputDir.mkdirs()

                t.logger.lifecycle("Looking for APK in: ${apkDir.absolutePath}")

                val apkFile = apkDir.listFiles()?.firstOrNull { it.extension == "apk" }
                if (apkFile == null) {
                    t.logger.warn("No APK found in ${apkDir.absolutePath}")
                    return
                }

                val outputFile = File(outputDir, "$pluginName.cgp")
                apkFile.copyTo(outputFile, overwrite = true)
                apkFile.delete()
                t.logger.lifecycle("Plugin assembled: ${outputFile.absolutePath}")
            }
        })
    }
}