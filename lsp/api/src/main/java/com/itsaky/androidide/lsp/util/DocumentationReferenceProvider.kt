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

package com.itsaky.androidide.lsp.util

import com.itsaky.androidide.lsp.models.ClassCompletionData
import com.itsaky.androidide.lsp.models.ICompletionData
import com.itsaky.androidide.lsp.models.MemberCompletionData
import com.itsaky.androidide.lsp.models.MethodCompletionData
import org.slf4j.LoggerFactory

/**
 * Provides the documentation tag for classes, methods, fields, etc.
 *
 * @author Akash Yadav
 */
object DocumentationReferenceProvider {

  /**
   * Package names whose documentation is most likely to be available on the Android Developers
   * website.
   */
  private val availablePackages =
    setOf(
      "android", // Android APIs
      "androidx", // AndroidX libraries
      "com.google.android.material", // Material Components
      "java" // Java APIs
    )


  @JvmStatic
  fun getTag(data: ICompletionData): String? {
    val klass =
      when (data) {
        is ClassCompletionData -> data
        is MemberCompletionData -> data.classInfo
        else -> return null
      }
    val baseName =
      if (klass.isNested) {
        klass.topLevelClass
      } else klass.className

    if (availablePackages.find { baseName.startsWith("$it.") } == null) {
      // This package is probably not listed on Android Developers documentation
      return null
    }

    val name = StringBuilder(baseName)

    if (klass.isNested) {
      name.append('.')
      name.append(klass.nameWithoutTopLevel)
    }

    if (data is MemberCompletionData) {
      name.append('#')
      name.append(data.memberName)
    }

    if (data is MethodCompletionData) {
      name.append('(')
      name.append(data.parameterTypes.joinToString(separator = ", "))
      name.append(')')
    }

    return name.toString()
  }
}
