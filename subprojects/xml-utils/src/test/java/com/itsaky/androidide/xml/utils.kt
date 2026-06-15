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

package com.itsaky.androidide.xml

import java.io.File

fun findAndroidJar(): File {
  val androidHome = findAndroidHome()
  val platformsDir = File(androidHome, "platforms")

  // Pick the newest installed platform's android.jar.  Hard-coding a fixed
  // list (e.g. 31/32/33) breaks every time CI ships only newer SDKs.
  val installed = platformsDir.listFiles { f ->
    f.isDirectory && f.name.startsWith("android-") && File(f, "android.jar").isFile
  }.orEmpty()

  return installed
    .mapNotNull { dir ->
      val apiLevel = dir.name.removePrefix("android-").toIntOrNull() ?: return@mapNotNull null
      apiLevel to File(dir, "android.jar")
    }
    .maxByOrNull { it.first }
    ?.second
    ?: throw RuntimeException(
      "Cannot find android.jar under $platformsDir (ANDROID_HOME=$androidHome)"
    )
}

fun findAndroidHome(): String {
  var androidHome = System.getenv("ANDROID_HOME")
  if (androidHome != null && androidHome.isNotBlank()) {
    return androidHome
  }

  androidHome = System.getenv("ANDROID_SDK_ROOT")
  if (androidHome != null && androidHome.isNotBlank()) {
    return androidHome
  }

  val os = System.getProperty("os.name")!!
  val home = System.getProperty("user.home")!!
  return if (os.contains("Linux")) {
    "$home/Android/Sdk"
  } else {
    "$home\\AppData\\Local\\Android\\Sdk"
  }
}
