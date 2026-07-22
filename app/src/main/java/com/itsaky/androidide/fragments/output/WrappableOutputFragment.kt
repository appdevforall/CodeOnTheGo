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

package com.itsaky.androidide.fragments.output

import com.itsaky.androidide.preferences.internal.EditorPreferences

/**
 * Interface for output fragments that support toggling word wrap.
 */
interface WrappableOutputFragment {
    /**
     * Preference key to persist word wrap for output fragments.
     */
    val wordWrapPrefKey: String
        get() = EditorPreferences.OUTPUT_WORD_WRAP

    /**
     * Set the word wrap state of the editor.
     */
    fun setWordWrapEnabled(enabled: Boolean)

    /**
     * Return the current word wrap state of the editor.
     */
    fun isWordWrapEnabled(): Boolean
}
