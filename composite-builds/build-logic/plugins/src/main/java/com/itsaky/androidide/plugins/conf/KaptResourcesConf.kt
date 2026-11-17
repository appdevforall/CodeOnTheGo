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

package com.itsaky.androidide.plugins.conf

import com.android.build.api.variant.AndroidComponentsExtension
import com.itsaky.androidide.plugins.tasks.CollectKaptResourcesTask
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.tasks.BaseKapt

/**
 * Packages resources that annotation processors generate through AGP 9's legacy kapt, so
 * `META-INF/services` entries written by AutoService reach the APK and the module's AAR.
 *
 * Without this, every `@AutoService` provider in an Android module is missing at runtime.
 */
fun Project.packageKaptGeneratedResources() {
	plugins.withId("com.android.legacy-kapt") {
		extensions.getByType(AndroidComponentsExtension::class.java).apply {
			onVariants { variant ->
				val variantName = variant.name.replaceFirstChar(Char::uppercaseChar)
				val kaptTaskName = "kapt${variantName}Kotlin"
				val collect =
					tasks.register("collect${variantName}KaptResources", CollectKaptResourcesTask::class.java) {
						kaptClassesDirectory.from(
							tasks.named(kaptTaskName, BaseKapt::class.java).flatMap { it.classesDir },
						)
					}

				variant.sources.resources?.addGeneratedSourceDirectory(
					collect,
					CollectKaptResourcesTask::resourcesDirectory,
				)
			}
		}
	}
}
