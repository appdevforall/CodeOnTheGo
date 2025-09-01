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

import com.itsaky.androidide.build.config.AGP_VERSION_MINIMUM
import com.itsaky.androidide.build.config.BuildConfig
import com.itsaky.androidide.build.config.ProjectConfig

plugins {
  id("org.jetbrains.kotlin.jvm")
  id("java-gradle-plugin")
}

description = "Gradle Plugin for projects that are built with AndroidIDE"

tasks.named<Test>("test") {
  useJUnitPlatform()
}

configurations {
  val androidBuildTool = create("androidBuildTool")

  getByName("compileOnly") {
    extendsFrom(androidBuildTool)
  }
  getByName("testImplementation") {
    extendsFrom(androidBuildTool)
  }
  findByName("integrationTestImplementation")?.run {
    extendsFrom(androidBuildTool)
  }
}

dependencies {
  implementation(libs.composite.constants)

  implementation(projects.gradlePluginConfig)
  implementation(projects.buildInfo)

  // use the AGP APIs from the minimum supported AGP version
  add("androidBuildTool", "com.android.tools.build:gradle:${AGP_VERSION_MINIMUM}")

  testImplementation(gradleTestKit())
  testImplementation(libs.tests.junit.jupiter)
  testImplementation(libs.tests.google.truth)
  testImplementation(projects.shared)

  testRuntimeOnly(libs.tests.junit.platformLauncher)
}

gradlePlugin {
  website.set(ProjectConfig.REPO_URL)
  vcsUrl.set(ProjectConfig.REPO_URL)

  plugins {
    create("initScriptPlugin") {
      id = "${BuildConfig.PACKAGE_NAME}.init"
      implementationClass = "${BuildConfig.PACKAGE_NAME}.gradle.AndroidIDEInitScriptPlugin"
      displayName = "AndroidIDE Init Script Gradle Plugin"
      description = "Init script Gradle plugin for projects that are built with AndroidIDE"
      tags.set(setOf("androidide", "init"))
    }

    create("gradlePlugin") {
      id = BuildConfig.PACKAGE_NAME
      implementationClass = "${BuildConfig.PACKAGE_NAME}.gradle.AndroidIDEGradlePlugin"
      displayName = "AndroidIDE Gradle Plugin"
      description = "Gradle plugin for projects that are built with AndroidIDE"
      tags.set(setOf("androidide", "gradle"))
    }

    create("logsenderPlugin") {
      id = "${BuildConfig.PACKAGE_NAME}.logsender"
      implementationClass = "${BuildConfig.PACKAGE_NAME}.gradle.LogSenderPlugin"
      displayName = "AndroidIDE LogSender Gradle Plugin"
      description = "Gradle plugin for applying LogSender-specific configuration to projects that are built with AndroidIDE"
      tags.set(setOf("androidide", "logsender"))
    }
  }
}

tasks.named<Jar>("jar") {
  archiveBaseName.set("cogo-plugin")
  archiveClassifier.set("") // Removes the default "all" classifier
//  archiveVersion.set("1.0.0")
  archiveVersion.set("")
  //  archiveFileName .set("cogo-plugin-1.0.0.jar") // Specify your desired JAR name
}