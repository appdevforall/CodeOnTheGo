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

import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.ApplicationVariant
import com.android.build.api.variant.Variant
import com.itsaky.androidide.buildinfo.BuildInfo
import com.itsaky.androidide.tooling.api.GradlePluginConfig._PROPERTY_IS_TEST_ENV
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.dsl.DependencyHandler

/**
 * @author Akash Yadav
 * Keywords: [android gradle plugin, agp, version, gradle, build, AndroidIDEInitScriptPlugin]
 * @see AndroidIDEInitScriptPlugin
 * related to
 * @see Project.ideDependency
 */

const val APP_PLUGIN = "com.android.application"
const val LIBRARY_PLUGIN = "com.android.library"

const val INSTALL_TASK_UNSUPPORTED_MESSAGE =
	"Code On The Go: adb is not available on the device, so Gradle cannot install the APK. " +
		"When this task runs from the IDE, Code On The Go installs the built APK itself."
const val UNINSTALL_TASK_UNSUPPORTED_MESSAGE =
	"Code On The Go: adb is not available on the device, so Gradle cannot uninstall the app. " +
		"Remove it from the device's Settings."
const val ANDROID_TEST_INSTALL_UNSUPPORTED_MESSAGE =
	"Code On The Go: adb is not available on the device, so instrumentation test APKs " +
		"cannot be installed or uninstalled from Gradle."

private const val AGP_INSTALL_TASK = "com.android.build.gradle.internal.tasks.InstallVariantTask"
private const val AGP_UNINSTALL_TASK = "com.android.build.gradle.internal.tasks.UninstallTask"

internal fun adbTaskReplacementMessage(task: Task): String? =
	when (
		generateSequence<Class<*>>(task.javaClass) { it.superclass }
			.map { it.name }
			.firstOrNull { it == AGP_INSTALL_TASK || it == AGP_UNINSTALL_TASK }
	) {
		AGP_INSTALL_TASK -> INSTALL_TASK_UNSUPPORTED_MESSAGE
		AGP_UNINSTALL_TASK -> UNINSTALL_TASK_UNSUPPORTED_MESSAGE
		else -> null
	}

internal val Project.isTestEnv: Boolean
	get() = hasProperty(_PROPERTY_IS_TEST_ENV) && property(
		_PROPERTY_IS_TEST_ENV
	).toString().toBoolean()

internal fun depVersion(testEnv: Boolean): String {
	return if (testEnv && !System.getenv("CI").toBoolean()) {
		BuildInfo.VERSION_NAME_SIMPLE
	} else {
		BuildInfo.VERSION_NAME_DOWNLOAD
	}
}

fun Project.ideDependency(artifact: String): Dependency {
	return dependencies.ideDependency(artifact, isTestEnv)
}

fun DependencyHandler.ideDependency(artifact: String, testEnv: Boolean): Dependency {
	return create("${BuildInfo.MVN_GROUP_ID}:${artifact}:${depVersion(testEnv)}")
}

/**
 * Perform the given [action] on debuggable variants in this [ApplicationAndroidComponentsExtension].
 */
fun ApplicationAndroidComponentsExtension.onDebuggableVariants(action: (ApplicationVariant) -> Unit) {
	val debuggableBuilds = hashSetOf<String>()

	beforeVariants { variantBuilder ->
		if (variantBuilder.debuggable) {
			debuggableBuilds.add(variantBuilder.name)
		}
	}

	onVariants { variant ->
		if (variant.name !in debuggableBuilds) {
			return@onVariants
		}

		action(variant)
	}
}

fun Variant.generateTaskName(prefix: String, suffix: String = "") =
	prefix + this.name.replaceFirstChar { it.uppercase() } + suffix
