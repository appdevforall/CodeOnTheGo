package com.itsaky.androidide.gradle

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.gradle.internal.tasks.factory.registerTask
import com.itsaky.androidide.gradle.tasks.AppDebuggerConfigTask
import com.itsaky.androidide.gradle.utils.JdwpOptions.Companion.jdwpOptions
import com.itsaky.androidide.tooling.api.ToolingConfig
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * @author Akash Yadav
 */
class JdwpConfigPlugin : Plugin<Project> {

    @Suppress("UnstableApiUsage")
    override fun apply(target: Project) = target.run {
        check(pluginManager.hasPlugin("com.android.application")) {
            "The 'com.android.application' plugin is required for JDWP configuration"
        }

        val directory = findProperty(ToolingConfig.PROP_JDWP_LIBDIR)?.toString()
            ?: throw GradleException("Property '${ToolingConfig.PROP_JDWP_LIBDIR}' not set")

        logger.info(
            "Trying to inject JDWP library directory '{}' to project '{}'",
            directory,
            project.path
        )

        val androidComponents = extensions.getByType(AndroidComponentsExtension::class.java)
                as ApplicationAndroidComponentsExtension

        val debuggableVariants = mutableListOf<String>()
        androidComponents.beforeVariants { variantBuilder ->
            if (variantBuilder.debuggable) {
                debuggableVariants.add(variantBuilder.name)
            }
        }

        androidComponents.onVariants { variant ->
            if (variant.name in debuggableVariants) {
                logger.info(
                    "Injecting JDWP library directory '{}' to variant '{}' of project '{}'",
                    directory,
                    variant.name,
                    project.path
                )

                // add the directory to the list of static sources
                variant.sources.jniLibs?.addStaticSourceDirectory(directory)

                val debuggerConfigTask = tasks.registerTask(
                    "${variant.name}DebuggerConfig",
                    AppDebuggerConfigTask::class.java
                )

                debuggerConfigTask.configure { task ->
                    task.jdwpOptions.set(project.jdwpOptions())
                }

                variant.sources.java?.addGeneratedSourceDirectory(
                    debuggerConfigTask,
                    AppDebuggerConfigTask::javaOut
                )

                variant.artifacts.use(debuggerConfigTask)
                    .wiredWithFiles(
                        AppDebuggerConfigTask::manifestIn,
                        AppDebuggerConfigTask::manifestOut
                    )
                    .toTransform(SingleArtifact.MERGED_MANIFEST)
            }
        }
    }
}