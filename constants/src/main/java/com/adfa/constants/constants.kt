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

package com.adfa.constants

import java.io.File

/**
 * @author Akash Yadav
 *
 * Keyword: [template-api, templateapi]
 * Moved from template-api in case of a roor repo merge we have to manuall move changes here.
 */

const val ANDROID_GRADLE_PLUGIN_VERSION = "8.0.0"
const val GRADLE_DISTRIBUTION_VERSION = "8.1.1"
const val KOTLIN_VERSION = "1.8.21"

val TARGET_SDK_VERSION = Sdk.Tiramisu
val COMPILE_SDK_VERSION = Sdk.Tiramisu

const val JAVA_SOURCE_VERSION = "11"
const val JAVA_TARGET_VERSION = "11"

// Constants that are supposed to be used to replace constants above for local builds

// Gradle folder
const val GRADLE_FOLDER_NAME = "gradle"
const val APG_SOURCE_FOLDER_NAME = "android_gradle_plugin"

// GradleWrapper
const val LOCAL_GRADLE_DISTRIBUTION_VERSION = "8.6"
const val GRADLE_VERSION = "gradle-${LOCAL_GRADLE_DISTRIBUTION_VERSION}"
const val GRADLE_WRAPPER_FILE_NAME = "${GRADLE_VERSION}-bin.zip"
val GRADLE_WRAPPER_PATH_SUFFIX = GRADLE_FOLDER_NAME + File.separator + "wrapper" + File.separator

// AGP
const val LOCAL_ANDROID_GRADLE_PLUGIN_VERSION = "2.7.1"
const val LOCAL_SOURCE_ANDROID_GRADLE_PLUGIN_VERSION_NAME =
    "gradle-plugin-${LOCAL_ANDROID_GRADLE_PLUGIN_VERSION}-beta.jar"
const val LOCAL_ANDROID_GRADLE_PLUGIN_NAME =
    "com.android.tools.build.gradle-${LOCAL_ANDROID_GRADLE_PLUGIN_VERSION}"
const val LOCAL_ANDROID_GRADLE_PLUGIN_JAR_NAME = "${LOCAL_ANDROID_GRADLE_PLUGIN_NAME}.jar"
/**
 * this line differes from LOCAL_ANDROID_GRADLE_PLUGIN_NAME by the : that we can use outside of gradle files.
 */
const val LOCAL_ANDROID_GRADLE_PLUGIN_DEPENDENCY_NAME = "com.android.tools.build:gradle:${LOCAL_ANDROID_GRADLE_PLUGIN_VERSION}"

// Termux
const val LOCAL_SOURCE_TERMUX_LIB_FOLDER_NAME = "termux"

// New tasks
const val COPY_GRADLE_EXECUTABLE_TASK_NAME = "copyGradleExecutable"
const val COPY_ANDROID_GRADLE_PLUGIN_EXECUTABLE_TASK_NAME = "copyAndroidGradlePluginExecutable"
const val COPY_TERMUX_LIBS_TASK_NAME = "copyTermuxLibs"


// Path
val ASSETS_COMMON_FOLDER = "data" + File.separator + "common"
const val SOURCE_LIB_FOLDER = "libs_source"
val LOCAL_TERMUX_LIB_FOLDER_PATH = File.separator + LOCAL_SOURCE_TERMUX_LIB_FOLDER_NAME