package com.itsaky.androidide.gradle

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.gradle.internal.tasks.factory.registerTask
import com.itsaky.androidide.gradle.tasks.AppDebuggerConfigTask
import com.itsaky.androidide.gradle.utils.JdwpOptions.Companion.jdwpOptions
import com.itsaky.androidide.tooling.api.ToolingConfig
import org.adfa.constants.JDWP_AAR_NAME
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import java.io.File

/**
 * @author Akash Yadav
 */
class JdwpConfigPlugin : Plugin<Project> {

    @Suppress("UnstableApiUsage")
    override fun apply(target: Project) = target.run {
        check(pluginManager.hasPlugin("com.android.application")) {
            "The 'com.android.application' plugin is required for JDWP configuration"
        }

        val jdwpDir = findProperty(ToolingConfig.PROP_JDWP_DIR)?.toString()?.let { File(it) }
            ?: throw GradleException("Property '${ToolingConfig.PROP_JDWP_DIR}' not set")
        val jdwpLibsDir = jdwpDir.resolve("jniLibs")
        val jniRemoteAar = jdwpDir.resolve(JDWP_AAR_NAME)

        logger.info(
            "Trying to inject JDWP library directory '{}' to project '{}'",
            jdwpLibsDir,
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
                    jdwpLibsDir,
                    variant.name,
                    project.path
                )

                // add the directory to the list of static sources
                variant.sources.jniLibs?.addStaticSourceDirectory(jdwpLibsDir.absolutePath)


                // compileConfiguration -> because we need to compile the auto-generated
                //                      Application class, which uses classes from this AAR file
                // runtimeConfiguration -> we need those classes to be present at runtime
                for (configuration in arrayOf(variant.compileConfiguration, variant.runtimeConfiguration)) {
                    configuration.dependencies.add(
                        project.dependencies.create(project.fileTree(jdwpDir) { tree ->
                            tree.include(
                                jniRemoteAar.name
                            )
                        })
                    )
                }

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