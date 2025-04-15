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
package com.itsaky.androidide.gradle

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.gradle.internal.tasks.factory.TaskConfigAction
import com.android.build.gradle.internal.tasks.factory.registerTask
import com.itsaky.androidide.gradle.tasks.AppDebuggerConfigTask
import com.itsaky.androidide.gradle.utils.JdwpOptions.Companion.jdwpOptions
import com.itsaky.androidide.tooling.api.ToolingConfig
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.logging.Logging

/**
 * Gradle Plugin for projects built in AndroidIDE.
 *
 * @author Akash Yadav
 */
class AndroidIDEGradlePlugin : Plugin<Project> {

    companion object {

        private val logger = Logging.getLogger(AndroidIDEGradlePlugin::class.java)
    }

    @Suppress("UnstableApiUsage")
    override fun apply(target: Project) {
        if (target.isTestEnv) {
            logger.lifecycle("Applying ${javaClass.simpleName} to project '${target.path}'")
        }

        target.run {
            val isLogSenderEnabled = false /*if (hasProperty(PROPERTY_LOGSENDER_ENABLED)) {
                property(PROPERTY_LOGSENDER_ENABLED).toString().toBoolean()
            } else {
                // enabled by default
                true
            }*/

            if (plugins.hasPlugin(APP_PLUGIN)) {
                if (isLogSenderEnabled) {
                    logger.info("Trying to apply LogSender plugin to project '${project.path}'")
                    pluginManager.apply(LogSenderPlugin::class.java)
                } else {
                    logger.warn("LogSender is disabled. Dependency will not be added to project '${project.path}'.")
                }

                if (findProperty(ToolingConfig.PROP_JDWP_INJECT)?.toString()?.toBoolean() == true) {
                    val directory = findProperty(ToolingConfig.PROP_JDWP_LIBDIR)?.toString()
                        ?: throw GradleException("Property '${ToolingConfig.PROP_JDWP_LIBDIR}' not set")

                    logger.info(
                        "Trying to inject JDWP library directory '{}' to project '{}'",
                        directory,
                        project.path
                    )

                    (extensions.getByType(AndroidComponentsExtension::class.java) as ApplicationAndroidComponentsExtension).apply {
                        val debuggableVariants = mutableListOf<String>()
                        beforeVariants { variantBuilder ->
                            if (variantBuilder.debuggable) {
                                debuggableVariants.add(variantBuilder.name)
                            }
                        }

                        onVariants { variant ->
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
            }
        }
    }
}
