package com.itsaky.androidide.plugins.build

import com.android.build.api.dsl.ApplicationExtension
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
            configurePackageId(target)
            createAssembleTask(target, extension, "debug")
            createAssembleTask(target, extension, "release")
        }
    }

    private fun configurePackageId(project: Project) {
        val android = project.extensions.findByType(ApplicationExtension::class.java) ?: return

        val existingParams = android.androidResources.additionalParameters
        val alreadyConfigured = existingParams.any { it == "--package-id" }
        if (alreadyConfigured) {
            project.logger.lifecycle("Plugin package-id already configured manually, skipping auto-assignment")
            return
        }

        val applicationId = android.defaultConfig.applicationId ?: project.group.toString()
        val packageId = generatePackageId(applicationId)
        val packageIdHex = "0x${packageId.toString(16).uppercase().padStart(2, '0')}"

        android.androidResources.additionalParameters(
            "--package-id", packageIdHex, "--allow-reserved-package-id"
        )

        project.logger.lifecycle("Auto-assigned plugin package-id: $packageIdHex (from applicationId: $applicationId)")
    }

    private fun generatePackageId(applicationId: String): Int {
        val hash = applicationId.hashCode() and 0x7FFFFFFF
        return (hash % 0x7D) + 0x02
    }

    private fun createAssembleTask(project: Project, extension: PluginBuilderExtension, variant: String) {
        val isDebug = variant == "debug"
        val taskName = if (isDebug) "assemblePluginDebug" else "assemblePlugin"
        val task = project.tasks.create(taskName)
        task.group = "build"
        task.description = "Assembles the $variant plugin and creates .cgp file"
        task.dependsOn("assemble${variant.replaceFirstChar { it.uppercase() }}")

        val pluginName = extension.pluginName.getOrElse(project.name)
        val apkDir = File(project.layout.buildDirectory.asFile.get(), "outputs/apk/$variant")
        val outputDir = File(project.layout.buildDirectory.asFile.get(), "plugin")
        val suffix = if (isDebug) "-debug" else ""

        task.doLast(object : org.gradle.api.Action<org.gradle.api.Task> {
            override fun execute(t: org.gradle.api.Task) {
                outputDir.mkdirs()

                t.logger.lifecycle("Looking for APK in: ${apkDir.absolutePath}")

                val apkFile = apkDir.listFiles()?.firstOrNull { it.extension == "apk" }
                if (apkFile == null) {
                    t.logger.warn("No APK found in ${apkDir.absolutePath}")
                    return
                }

                val outputFile = File(outputDir, "$pluginName$suffix.cgp")
                apkFile.copyTo(outputFile, overwrite = true)
                apkFile.delete()
                t.logger.lifecycle("Plugin assembled: ${outputFile.absolutePath}")
            }
        })
    }
}