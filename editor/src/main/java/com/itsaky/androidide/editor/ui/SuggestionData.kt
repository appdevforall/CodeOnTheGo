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

package com.itsaky.androidide.editor.ui

import com.itsaky.androidide.models.Position

/**
 * Data class representing an inline code suggestion.
 *
 * @property text Multi-line suggestion text
 * @property startPosition Position where suggestion starts in the document
 * @property cursorLine Line number where cursor was when suggestion requested
 * @property cursorColumn Column number where cursor was when suggestion requested
 * @property requestTimestamp Unix timestamp when suggestion was requested (for cache expiry)
 */
data class SuggestionData(
    val text: String,
    val startPosition: Position,
    val cursorLine: Int,
    val cursorColumn: Int,
    val requestTimestamp: Long
)
