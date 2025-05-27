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

import com.itsaky.androidide.tooling.api.LogSenderConfig.PROPERTY_LOGSENDER_ENABLED
import com.itsaky.androidide.tooling.api.ToolingConfig.PROP_JDWP_INJECT
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

    override fun apply(target: Project) {
        if (target.isTestEnv) {
            logger.lifecycle("Applying ${javaClass.simpleName} to project '${target.path}'")
        }

        target.run {
            val isLogSenderEnabled = /*findProperty(PROPERTY_LOGSENDER_ENABLED) == "true"*/ false
            val isDebuggerEnabled = findProperty(PROP_JDWP_INJECT) == "true"

            if (plugins.hasPlugin(APP_PLUGIN)) {
                if (isLogSenderEnabled) {
                    logger.info("Applying LogSender plugin to project '${project.path}'")
                    pluginManager.apply(LogSenderPlugin::class.java)
                }

                if (isDebuggerEnabled) {
                    logger.info("Applying JdwpConfig plugin to project '${project.path}'")
                    pluginManager.apply(JdwpConfigPlugin::class.java)
                }
            }
        }
    }
}
