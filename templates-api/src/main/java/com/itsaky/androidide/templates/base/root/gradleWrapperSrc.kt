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

import org.adfa.constants.COMPOSE_GRADLE_WRAPPER_FILE_NAME
import org.adfa.constants.GRADLE_WRAPPER_FILE_NAME
import org.adfa.constants.GRADLE_DISTS

fun gradleWrapperPropsSrc(isToml: Boolean): String {
    val gradleVersion = if(isToml) COMPOSE_GRADLE_WRAPPER_FILE_NAME else GRADLE_WRAPPER_FILE_NAME
    return """
    distributionBase=GRADLE_USER_HOME
    distributionPath=wrapper/dists
    distributionUrl=https\://services.gradle.org/distributions/${gradleVersion}
    networkTimeout=10000
    zipStoreBase=GRADLE_USER_HOME
    zipStorePath=wrapper/dists
  """.trimIndent()
}