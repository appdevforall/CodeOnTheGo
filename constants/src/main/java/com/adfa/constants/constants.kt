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

const val ANDROID_GRADLE_PLUGIN_VERSION = "8.5.0"
const val GRADLE_DISTRIBUTION_VERSION = "8.0.0"
const val KOTLIN_VERSION = "1.9.22"

val TARGET_SDK_VERSION = Sdk.Tiramisu
val COMPILE_SDK_VERSION = Sdk.Tiramisu
val COMPOSE_SDK_VERSION = Sdk.Tiramisu

const val JAVA_SOURCE_VERSION = "17"
const val JAVA_TARGET_VERSION = "17"

// Constants that are supposed to be used to replace constants above for local builds
// Path
val ASSETS_COMMON_FOLDER = "data" + File.separator + "common"
const val SOURCE_LIB_FOLDER = "libs_source"
const val HOME_PATH = "home"
const val ANDROID_SDK_PATH = "android-sdk"
const val ANDROID_SDK_ZIP = "android-sdk.zip"
const val USR = "usr"

// Gradle folder
const val GRADLE_FOLDER_NAME = "gradle"
const val APG_SOURCE_FOLDER_NAME = "android_gradle_plugin"

// GradleWrapper
const val LOCAL_GRADLE_DISTRIBUTION_VERSION = "8.0"
const val LOCAL_COMPOSE_GRADLE_DISTRIBUTION_VERSION = "8.7"
const val GRADLE_VERSION = "gradle-${LOCAL_GRADLE_DISTRIBUTION_VERSION}"
const val COMPOSE_GRADLE_VERSION = "gradle-${LOCAL_COMPOSE_GRADLE_DISTRIBUTION_VERSION}"
const val GRADLE_WRAPPER_FILE_NAME = "${GRADLE_VERSION}-bin.zip"
const val COMPOSE_GRADLE_WRAPPER_FILE_NAME = "${COMPOSE_GRADLE_VERSION}-bin.zip"
val GRADLE_WRAPPER_PATH_SUFFIX = GRADLE_FOLDER_NAME + File.separator + "wrapper" + File.separator

// AGP
const val LOCAL_ANDROID_GRADLE_PLUGIN_VERSION = "8.0.0"
const val DEST_LOCAL_ANDROID_GRADLE_PLUGIN_VERSION = "8.0.0"

const val KOTLIN_LOCAL_ANDROID_GRADLE_PLUGIN_VERSION = "1.8.0"

const val LOCAL_SOURCE_ANDROID_GRADLE_PLUGIN_VERSION_NAME =
    "gradle-${LOCAL_ANDROID_GRADLE_PLUGIN_VERSION}.jar"
const val LOCAL_SOURCE_ANDROID_KOTLIN_GRADLE_PLUGIN_VERSION_NAME =
    "kotlin-gradle-plugin-${KOTLIN_LOCAL_ANDROID_GRADLE_PLUGIN_VERSION}.jar"

const val LOCAL_ANDROID_GRADLE_PLUGIN_NAME =
    "gradle-${DEST_LOCAL_ANDROID_GRADLE_PLUGIN_VERSION}"
const val LOCAL_ANDROID_GRADLE_PLUGIN_JAR_NAME = "${LOCAL_ANDROID_GRADLE_PLUGIN_NAME}.jar"
const val ANDROID_KOTLIN_GRADLE_PLUGIN_VERSION_NAME =
    "kotlin-gradle-plugin-${KOTLIN_LOCAL_ANDROID_GRADLE_PLUGIN_VERSION}.jar"

/**
 * this line differes from LOCAL_ANDROID_GRADLE_PLUGIN_NAME by the : that we can use outside of gradle files.
 */
const val LOCAL_ANDROID_GRADLE_PLUGIN_DEPENDENCY_NAME =
    "com.android.tools.build:gradle:${DEST_LOCAL_ANDROID_GRADLE_PLUGIN_VERSION}"

//ABI
const val V7_KEY = "v7"
const val V8_KEY = "v8"
const val ARM_KEY = "armeabi"

//Bootstrap
const val BOOTSTRAP_SOURCE_FOLDER = "bootstrap"

// Termux
const val LOCAL_SOURCE_TERMUX_LIB_FOLDER_NAME = "termux"
val TERMUX_DEBS_PATH = "cache" + File.separator + "apt" + File.separator + "archives"
const val MANIFEST_FILE_NAME = "manifest.json"
const val LOCAL_SOURCE_TERMUX_VAR_FOLDER_NAME = "var"
const val DESTINATION_TERMUX_VAR_FOLER_PATH = "$USR/$LOCAL_SOURCE_TERMUX_VAR_FOLDER_NAME"
const val LOCAL_SOURCE_USR_FOLDER = USR
const val DESSTINATION_USR_FOLDER = USR

// Gradle Caches
const val LOACL_GRADLE_8_0_0_CACHES_PATH = "gradle"
const val LOACL_SOURCE_AGP_8_0_0_CACHES = "gradle"
const val DEST_GRADLE_FOLDER_NAME = ".gradle"
const val GRADLE_ZIP_FILE_NAME = "gradle.zip"
const val GRADDLE_WRAPPER_PART_FILE_NAME = "wrapper.zip"
val LOACL_AGP_8_0_0_CACHES_DEST = HOME_PATH + File.separator + DEST_GRADLE_FOLDER_NAME
const val LOACL_SOURCE_AGP_8_5_1_CACHES = "files-2.1-8.5.1/files-2.1"

//Local maven repo
const val LOCAL_MAVEN_REPO_ARCHIVE_ZIP_NAME = "localMvnRepository.zip"
val LOACL_MAVEN_CACHES_DEST = HOME_PATH + File.separator + "maven"
const val LOCAL_MAVEN_REPO_FROLDER_DEST = "localMvnRepository"

// SDK
const val LOCAL_SOURCE_ANDROID_SDK = "androidsdk"
const val DESTINATION_ANDROID_SDK = "${HOME_PATH}/${ANDROID_SDK_PATH}"

// Platform Tools
const val LOCAL_PALTFORM_TOOLS = "platformtools"
const val DESTINATION_PLATFORM_TOOLS = "${HOME_PATH}/${ANDROID_SDK_PATH}/platform-tools"

// New tasks
const val COPY_GRADLE_EXECUTABLE_TASK_NAME = "copyGradleExecutable"
const val COPY_ANDROID_GRADLE_PLUGIN_EXECUTABLE_TASK_NAME = "copyAndroidGradlePluginExecutable"
const val COPY_TERMUX_LIBS_TASK_NAME = "copyTermuxLibs"
const val COPY_GRADLE_CAHCES_TO_ASSETS = "copyGradleCachesToAssets"
const val COPY_ANDROID_SDK_TO_ASSETS = "copyAndroidSdkToAssets"
const val COPY_PLATFORM_TOOLS_TO_ASSETS = "copyPlatfromToolsToAssets"
const val COPY_USR_FOLDER_TO_ASSETS = "copyUsrFolderToAssets"
const val COPY_LOCAL_MAVEN_REPO_TO_ASSETS = "copyLocalMavenRepoToAssets"

// Tooltips
const val CONTENT_KEY = "CONTENT_KEY"

// Toml
const val TOML_FILE_NAME = "libs.versions.toml"