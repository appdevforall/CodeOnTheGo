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

/**
 * State machine for inline suggestion lifecycle.
 *
 * Transitions:
 * IDLE → WAITING → REQUESTING → SHOWING → ACCEPTING/IDLE
 */
enum class SuggestionState {
    /** No suggestion active */
    IDLE,

    /** Characters typed, waiting for debounce timer */
    WAITING,

    /** LLM request in flight */
    REQUESTING,

    /** LLM request in flight, showing the "Loading suggestion..." placeholder */
    LOADING,

    /** Suggestion visible as ghost text */
    SHOWING,

    /** Tab pressed, committing suggestion */
    ACCEPTING
}
