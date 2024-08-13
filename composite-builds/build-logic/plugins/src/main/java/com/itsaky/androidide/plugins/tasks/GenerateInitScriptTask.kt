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

package com.itsaky.androidide.plugins.tasks

import com.adfa.constants.GRADLE_FOLDER_NAME
import com.adfa.constants.LOCAL_ANDROID_GRADLE_PLUGIN_JAR_NAME
import com.itsaky.androidide.build.config.VersionUtils
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

/**
 * Keywords: [init.gradle, gradle, gradle plugin, initscript, 2.7.1, 8.5.1]
 * This code generates init.gradle script that is reponsible for all project gradle repos and
 * gradle plugin classpath.
 * Generates the Gradle init script for AndroidIDE.
 * This script is also stored at
 * ~/AndroidIDE/app/build/intermediates/assets/debug/mergeDebugAssets/data/common
 *
 * I have replaced itsaky gradle plugin with a local jar.
 */
abstract class GenerateInitScriptTask : DefaultTask() {

  @get:Input
  abstract val downloadVersion: Property<String>

  @get:Input
  abstract val mavenGroupId: Property<String>

  @get:OutputDirectory
  abstract val outputDir: DirectoryProperty

  @TaskAction
  fun generate() {

    val outFile = this.outputDir.file("data/common/androidide.init.gradle")
      .also {
        it.get().asFile.parentFile.mkdirs()
      }

    outFile.get().asFile.bufferedWriter().use {

      it.write(
        """
      initscript {
          repositories {
              
              // Always specify the snapshots repository first
              maven {
                  // Add snapshots repository for AndroidIDE CI builds
                  url "${VersionUtils.SONATYPE_SNAPSHOTS_REPO}"
              }
              
              maven {
                  // Add public repository for AndroidIDE release builds
                  url "${VersionUtils.SONATYPE_PUBLIC_REPO}"
              }
              
              // This values is hardcoded in GenerateInitScriptTask
              // It was taking way too much time to properly provide it with module dependencies.
              // But we should invest more time into this later.
              flatDir {
                  dirs 'gradle' // Directory containing your local JAR
              }
              
              mavenCentral()
              google()
          }

          dependencies {
              classpath(files("$LOCAL_ANDROID_GRADLE_PLUGIN_JAR_NAME"))
          }
      }
      
      //apply plugin: com.itsaky.androidide.gradle.AndroidIDEInitScriptPlugin
    """
          .trimIndent()
      )
    }
  }

}