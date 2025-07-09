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

package com.itsaky.androidide.utils

import android.content.ComponentName
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources.Theme
import android.provider.Settings
import android.util.TypedValue

/**
 * Check if the given accessibility service is enabled.
 */
inline fun <reified T> Context.isAccessibilityEnabled(): Boolean {
  try {
    val enabled = Settings.Secure.getInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED)
    if (enabled != 1) return false

    val name = ComponentName(applicationContext, T::class.java)
    val services = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
    return services?.contains(name.flattenToString()) ?: false
  } catch (e: Settings.SettingNotFoundException) {
    return false
  }
}

fun Context.isSystemInDarkMode(): Boolean {
  return this.resources.configuration.isSystemInDarkMode()
}

fun Configuration.isSystemInDarkMode(): Boolean {
  return (uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
}

@JvmOverloads
fun Context.resolveAttr(id: Int, resolveRefs: Boolean = true): Int {
  return theme.resolveAttr(id, resolveRefs)
}

@JvmOverloads
fun Theme.resolveAttr(id: Int, resolveRefs: Boolean = true): Int =
  TypedValue().let {
    resolveAttribute(id, it, resolveRefs)
    it.data
  }
