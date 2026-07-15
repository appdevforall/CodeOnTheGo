package com.itsaky.androidide.plugins.build

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class PluginBuilder : Plugin<Project> {

    private enum class BuildVariant(
        val variantName: String,
        val taskName: String,
        val fileSuffix: String
    ) {
        DEBUG("debug", "assemblePluginDebug", "-debug"),
        RELEASE("release", "assemblePlugin", "")
    }

    companion object {
        private val TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
        private const val DEFAULT_VERSION = "1.0.0"
        private const val POSITIVE_HASH_MASK = 0x7FFFFFFF
        private const val PACKAGE_ID_RANGE = 0x7D
        private const val MIN_PACKAGE_ID = 0x02
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
            configurePackageId(target)
            BuildVariant.values().forEach { variant ->
                createAssembleTask(target, extension, variant)
            }
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
        val hash = applicationId.hashCode() and POSITIVE_HASH_MASK
        return (hash % PACKAGE_ID_RANGE) + MIN_PACKAGE_ID
    }

    private fun createAssembleTask(project: Project, extension: PluginBuilderExtension, variant: BuildVariant) {
        val task = project.tasks.create(variant.taskName)
        task.group = "build"
        task.description = "Assembles the ${variant.variantName} plugin and creates .cgp file"
        task.dependsOn("assemble${variant.variantName.replaceFirstChar { it.uppercase() }}")

        val pluginName = extension.pluginName.getOrElse(project.name)
        val apkDir = File(project.layout.buildDirectory.asFile.get(), "outputs/apk/${variant.variantName}")
        val outputDir = File(project.layout.buildDirectory.asFile.get(), "plugin")

        task.doLast(object : org.gradle.api.Action<org.gradle.api.Task> {
            override fun execute(t: org.gradle.api.Task) {
                outputDir.mkdirs()

                t.logger.lifecycle("Looking for APK in: ${apkDir.absolutePath}")

                val apkFile = apkDir.listFiles()?.firstOrNull { it.extension == "apk" }
                if (apkFile == null) {
                    t.logger.warn("No APK found in ${apkDir.absolutePath}")
                    return
                }

                val outputFile = File(outputDir, "$pluginName${variant.fileSuffix}.cgp")
                apkFile.copyTo(outputFile, overwrite = true)
                apkFile.delete()
                t.logger.lifecycle("Plugin assembled: ${outputFile.absolutePath}")
            }
        })
    }
}
