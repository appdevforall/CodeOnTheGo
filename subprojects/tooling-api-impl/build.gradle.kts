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

import com.itsaky.androidide.build.config.BuildConfig

@Suppress("JavaPluginLanguageLevel")
plugins {
  id("com.github.johnrengelman.shadow") version "8.1.1"
  id("java-library")
  id("kotlin-kapt")
  id("org.jetbrains.kotlin.jvm")
}

tasks.withType<Jar> {
  manifest { attributes("Main-Class" to "${BuildConfig.packageName}.tooling.impl.Main") }
}

tasks.register("deleteExistingJarFiles") {
  delete {
    delete(project.layout.buildDirectory.dir("libs"))
  }
}

/**
 * Keywords: [ local Jars, libs, ToolsManager, ResourceUtils, assets ]
 * This method gets tooling jar from: ~/AndroidIDE/app/build/intermediates/assets/debug/mergeDebugAssets/data/common
 * and puts them under: ~/AndroidIDE/subprojects/tooling-api-impl/build
 * Why do we need to copy this, when ToolsManager still uses data/common foler? Please tell me.
 * Why does it need a generated folder to hold dependencies? Not a guess.
 * Why? So far I have no idea.
 * How it gets to the source path to get the jars without explicitly specifing it? Not a clue.
 * There are other methods that handle other jars from data/common folder.
 *
 * Task execution order is the follwowing:
 * 1) jar
 * 2) deleteExistingJarFiles
 * 3) shadowJar
 * 4) copyJar
 */
tasks.register("copyJar") {
  doLast {
    val libsDir = project.layout.buildDirectory.dir("libs")

    copy {
      from(libsDir)
      into(libsDir)
      include("*-all.jar")
      rename { "tooling-api-all.jar" }
    }
  }
}

project.tasks.getByName("jar") {
  dependsOn("deleteExistingJarFiles")
  finalizedBy("shadowJar")
}

project.tasks.getByName("shadowJar") {
  finalizedBy("copyJar")
}

dependencies {
  kapt(libs.google.auto.service)

  api(projects.subprojects.toolingApi)

  implementation(projects.buildInfo)
  implementation(projects.shared)

  implementation(libs.common.jkotlin)
  implementation(libs.google.auto.service.annotations)
  implementation(libs.xml.xercesImpl)
  implementation(libs.xml.apis)
  implementation(libs.tooling.gradleApi)

  testImplementation(projects.testing.tooling)

  runtimeOnly(libs.tooling.slf4j)
}
