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

package com.itsaky.androidide.templates.base.root

import org.adfa.constants.DEST_LOCAL_ANDROID_GRADLE_PLUGIN_VERSION
import com.itsaky.androidide.templates.Language
import com.itsaky.androidide.templates.base.ProjectTemplateBuilder

internal fun ProjectTemplateBuilder.buildGradleSrcKts(): String {
  return """
    // Top-level build file where you can add configuration options common to all sub-projects/modules.
    plugins {
        id("com.android.application") apply false version "$DEST_LOCAL_ANDROID_GRADLE_PLUGIN_VERSION"
        id("com.android.library") apply false version "$DEST_LOCAL_ANDROID_GRADLE_PLUGIN_VERSION"
    }

    tasks.register<Delete>("clean") {
        delete(rootProject.layout.buildDirectory)
    }
  """.trimIndent()
}

internal fun ProjectTemplateBuilder.buildGradleSrcGroovy(): String {
  return """
    // Top-level build file where you can add configuration options common to all sub-projects/modules.
    plugins {
        id 'com.android.application' version "$DEST_LOCAL_ANDROID_GRADLE_PLUGIN_VERSION" apply false
        id 'com.android.library' version "$DEST_LOCAL_ANDROID_GRADLE_PLUGIN_VERSION" apply false
    }

    task clean(type: Delete) {
        delete rootProject.layout.buildDirectory
    }
  """.trimIndent()
}

internal fun ProjectTemplateBuilder.buildGradleSrcKtsToml(): String {
  return """
    plugins {
        alias(libs.plugins.android.application) apply false
        alias(libs.plugins.jetbrains.kotlin.android) apply false
    }

    tasks.register<Delete>("clean") {
        delete(rootProject.layout.buildDirectory)
    }
  """.trimIndent()
}

private fun ProjectTemplateBuilder.ktPlugin() = if (data.language == Language.Kotlin) {
  if (data.useKts) ktPluginKts() else ktPluginGroovy()
} else ""

private fun ProjectTemplateBuilder.ktPluginKts(): String {
  return """id("org.jetbrains.kotlin.android") version "${data.version.kotlin}" apply false"""
}

private fun ProjectTemplateBuilder.ktPluginGroovy(): String {
  return "id 'org.jetbrains.kotlin.android' version '${data.version.kotlin}' apply false"
}
