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

package org.adfa.constants

const val ANDROID_GRADLE_PLUGIN_VERSION = "8.11.0"
const val GRADLE_DISTRIBUTION_VERSION = "8.14.3"
const val KOTLIN_VERSION = "1.9.22"

val TARGET_SDK_VERSION = Sdk.Tiramisu
val COMPILE_SDK_VERSION = Sdk.Tiramisu
val COMPOSE_SDK_VERSION = Sdk.Tiramisu

const val JAVA_SOURCE_VERSION = "17"
const val JAVA_TARGET_VERSION = "17"

const val HOME_PATH = "home"
const val ANDROID_SDK_ZIP = "android-sdk.zip"

const val GRADLE_DISTRIBUTION_NAME = "gradle-$GRADLE_DISTRIBUTION_VERSION"
const val GRADLE_DISTRIBUTION_ARCHIVE_NAME = "$GRADLE_DISTRIBUTION_NAME-bin.zip"

// .androidide folder
@Suppress("SdCardPath")
const val ANDROIDIDE_HOME = "/data/data/com.itsaky.androidide/files/home/.androidide"

// Cogo gradle plugin
const val COGO_GRADLE_PLUGIN_NAME = "cogo-plugin"
const val COGO_GRADLE_PLUGIN_JAR_NAME = "$COGO_GRADLE_PLUGIN_NAME.jar"
const val COGO_GRADLE_PLUGIN_PATH = "$ANDROIDIDE_HOME/plugin"

// dists folder for gradle-<version>-bin.zip files
const val GRADLE_DISTRIBUTIONS_DIR = "$ANDROIDIDE_HOME/gradle-dists"


//ABI
const val V7_KEY = "v7"
const val V8_KEY = "v8"
const val ARM_KEY = "armeabi"

//Local maven repo
const val LOCAL_MAVEN_REPO_ARCHIVE_ZIP_NAME = "localMvnRepository.zip"
const val LOCAL_MAVEN_CACHES_DEST = "$HOME_PATH/maven"
const val LOCAL_MAVEN_REPO_FOLDER_DEST = "localMvnRepository"

@Suppress("SdCardPath")
const val MAVEN_LOCAL_REPOSITORY = "/data/data/com.itsaky.androidide/files/$LOCAL_MAVEN_CACHES_DEST/$LOCAL_MAVEN_REPO_FOLDER_DEST"

// Tooltips
const val CONTENT_KEY = "CONTENT_KEY"
const val CONTENT_TITLE_KEY = "CONTENT_TITLE_KEY"

// Toml
const val TOML_FILE_NAME = "libs.versions.toml"

// Help
const val HELP_PAGE_URL =
	"file:///android_asset/CoGoTooltips/html/cogohelp/index.html"

// Documentation
const val DOCUMENTATION_DB = "documentation.db"

const val LOGSENDER_AAR_NAME = "logsender.aar"

// Generated Gradle Api Jar
const val GRADLE_API_NAME_JAR = "gradle-api-$GRADLE_DISTRIBUTION_VERSION.jar"
const val GRADLE_API_NAME_JAR_ZIP = "${GRADLE_API_NAME_JAR}.zip"
const val GRADLE_API_NAME_JAR_BR = "${GRADLE_API_NAME_JAR}.br"
