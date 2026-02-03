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

import android.graphics.Color

/**
 * Converts this Android ARGB color int to a CSS 8-digit hex string (#RRGGBBAA).
 * CSS uses alpha last; Android uses alpha first.
 */
fun Int.toCssHex(): String =
  "#%02X%02X%02X%02X".format(
    Color.red(this),
    Color.green(this),
    Color.blue(this),
    Color.alpha(this),
  )

/**
 * Parses the given [hex color string][hexColor] and returns the [Long] representing
 * the color.
 *
 * @param hexColor The color string.
 * @return The parsed color value.
 * @throws IllegalArgumentException If there was an error parsing the hex color.
 */
fun parseHexColor(hexColor: String) : Long {
  var str = hexColor
  if (str[0] == '#') {
    str = str.substring(1)
  }
  return try {
    if (str.length == 3) {
      // HEX color is in the form of #FFF
      // convert it to #FFFFFF format (6 character long)
      val r = str[0]
      val g = str[1]
      val b = str[2]
      str = "$r$r$g$g$b$b"
    }

    if (str.length == 6) {
      // Prepend alpha value
      str = "FF${str}"
    }

    java.lang.Long.parseLong(str, 16)
  } catch (e: Exception) {
    throw IllegalArgumentException("Failed to parse hex color. color=$hexColor, processedColorString=$str", e)
  }
}