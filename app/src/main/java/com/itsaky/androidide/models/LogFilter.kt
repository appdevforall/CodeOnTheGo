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

package com.itsaky.androidide.models

import com.itsaky.androidide.utils.ILogger
import java.util.EnumSet

/**
 * A filter for log lines shown in the output views.
 *
 * @property enabledLevels Log levels to show. Lines without a known level always pass.
 * @property text Case-insensitive substring that a line must contain to be shown.
 */
data class LogFilter(
	val enabledLevels: Set<ILogger.Level> = ALL_LEVELS,
	val text: String = "",
) {
	companion object {
		val ALL_LEVELS: Set<ILogger.Level> = EnumSet.allOf(ILogger.Level::class.java)

		val NONE = LogFilter()
	}

	val isActive: Boolean
		get() = text.isNotEmpty() || enabledLevels.size < ALL_LEVELS.size

	fun matches(
		level: ILogger.Level?,
		line: String,
	): Boolean {
		if (level != null && level !in enabledLevels) {
			return false
		}
		return text.isEmpty() || line.contains(text, ignoreCase = true)
	}
}
