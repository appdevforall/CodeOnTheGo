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

import com.android.build.api.component.analytics.AnalyticsEnabledApplicationVariant
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.ApplicationVariant
import com.android.build.api.variant.impl.ApplicationVariantImpl
import com.itsaky.androidide.tooling.api.GradlePluginConfig.PROPERTY_LOG_SENDER_AAR
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.logging.Logging
import java.io.File
import java.io.FileNotFoundException

/**
 * Plugin to manage LogSender in Android applications.
 *
 * @author Akash Yadav
 */

class LogSenderPlugin : Plugin<Project> {
	companion object {
		private val logger = Logging.getLogger(LogSenderPlugin::class.java)
	}

	override fun apply(target: Project) {
		if (!target.plugins.hasPlugin(APP_PLUGIN)) {
			return
		}

		logger.info("Applying {} to project '${target.path}'", LogSenderPlugin::class.simpleName)

		if (target.isTestEnv) {
			logger.lifecycle("Applying {} to project '{}'", javaClass.simpleName, target.path)
		}

		val logsenderAar =
			target.findProperty(PROPERTY_LOG_SENDER_AAR)?.let { aarPath -> File(aarPath.toString()) }
				?: throw GradleException("LogSenderPlugin has been applied but no property '$PROPERTY_LOG_SENDER_AAR' is set")

		if (!logsenderAar.exists()) {
			throw FileNotFoundException("LogSender AAR file not found at '${logsenderAar.absolutePath}'")
		}

		if (!logsenderAar.isFile) {
			throw GradleException("LogSender AAR file at '${logsenderAar.absolutePath}' is not a file")
		}

		target.run {
			check(plugins.hasPlugin(APP_PLUGIN)) {
				"${javaClass.simpleName} can only be applied to Android application projects."
			}

			extensions.getByType(ApplicationAndroidComponentsExtension::class.java).apply {
				onDebuggableVariants { variant ->
					variant.withRuntimeConfiguration {
						logger.lifecycle(
							"Adding LogSender dependency to variant '{}' of project '{}'",
							variant.name,
							project.path,
						)

						logger.debug("Adding logsender dependency: {}", logsenderAar.absolutePath)
						dependencies.add(project.dependencies.create(project.fileTree(logsenderAar)))
					}
				}
			}
		}
	}

	private fun ApplicationVariant.withRuntimeConfiguration(action: Configuration.() -> Unit) {
		if (this is ApplicationVariantImpl) {
			variantDependencies.runtimeClasspath.action()
		} else if (this is AnalyticsEnabledApplicationVariant) {
			delegate.withRuntimeConfiguration(action)
		}
	}
}
