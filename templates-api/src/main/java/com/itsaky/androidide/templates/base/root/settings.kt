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

import com.adfa.constants.LOACL_MAVEN_CACHES_DEST
import com.adfa.constants.LOCAL_MAVEN_REPO_FROLDER_DEST
import com.itsaky.androidide.templates.base.ProjectTemplateBuilder

/**
 * Keyword: [com.android.application, tools.build.gradle, gradle plugin]
 * I have added com.android.tools.build.gradle plugin. From what I see
 * it's a wrapper plugin around com.android.application / com.android.library
 * I was not able to find aa standalone com.android.application jar.
 */
internal fun ProjectTemplateBuilder.settingsGradleSrcStr(): String {
  return """
pluginManagement {
  repositories {
    maven(uri("/data/data/com.itsaky.androidide/files/$LOACL_MAVEN_CACHES_DEST/$LOCAL_MAVEN_REPO_FROLDER_DEST"))
  }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    maven(uri("/data/data/com.itsaky.androidide/files/$LOACL_MAVEN_CACHES_DEST/$LOCAL_MAVEN_REPO_FROLDER_DEST"))
  }
}

rootProject.name = "${data.name}"

${modules.joinToString(separator = ", ") { "include(\"${it.name}\")" }}    
  """.trim()
}

internal fun ProjectTemplateBuilder.settingsGroovyGradleSrcStr(): String {
  return """
pluginManagement {
    repositories {
        maven { url = uri("/data/data/com.itsaky.androidide/files/$LOACL_MAVEN_CACHES_DEST/$LOCAL_MAVEN_REPO_FROLDER_DEST") }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("/data/data/com.itsaky.androidide/files/$LOACL_MAVEN_CACHES_DEST/$LOCAL_MAVEN_REPO_FROLDER_DEST") }
    }
}

rootProject.name = "${data.name}"

${modules.joinToString(separator = ", ") { "include(\"${it.name}\")" }}    
  """.trim()
}