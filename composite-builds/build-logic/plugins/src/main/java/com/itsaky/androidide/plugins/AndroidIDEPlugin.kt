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

import com.android.build.api.dsl.ApplicationExtension
import com.itsaky.androidide.build.config.isFDroidBuild
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Manages plugins applied on the IDE's project modules.
 *
 * @author Akash Yadav
 */
class AndroidIDEPlugin : Plugin<Project> {

	override fun apply(target: Project) {
		target.run {
			if (project.path == rootProject.path) {
				throw GradleException("Cannot apply ${AndroidIDEPlugin::class.simpleName} to root project")
			}

			if (!project.buildFile.exists() || !project.buildFile.isFile) {
				return@run
			}

			val isAppModule = plugins.hasPlugin("com.android.application")
			val isAndroidModule =  isAppModule ||
					plugins.hasPlugin("com.android.library")

			if (isAppModule && !isFDroidBuild) {
				// setup signing configuration
				plugins.apply(SigningConfigPlugin::class.java)
			}

			if (isFDroidBuild && project.path == ":app") {
				val appExtension = extensions.getByType(ApplicationExtension::class.java)
				logger.warn("Building for F-Droid with configuration:")
				logger.warn("applicationId = ${appExtension.defaultConfig.applicationId}")
				logger.warn("versionName = ${appExtension.defaultConfig.versionName}")
				logger.warn("versionCode = ${appExtension.defaultConfig.versionCode}")
				logger.warn("--- x --- x ---")
			}

			val taskName = when {
				isAndroidModule -> "testDebugUnitTest"
				else -> "test"
			}

			logger.info("${project.path} will run task '$taskName' for tests in CI")

			project.tasks.create("runTestsInCI") {
				dependsOn(taskName)
			}
		}
	}
}
