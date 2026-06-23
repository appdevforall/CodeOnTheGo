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

package com.itsaky.androidide.preferences.internal

/**
 * Preferences for inline code suggestions.
 */
@Suppress("MemberVisibilityCanBePrivate")
object InlineSuggestionPreferences {

    const val ENABLED = "inline_suggestion_enabled"
    const val AUTO_TRIGGER = "inline_suggestion_auto_trigger"
    const val CHAR_THRESHOLD = "inline_suggestion_char_threshold"
    const val DEBOUNCE_MS = "inline_suggestion_debounce_ms"
    const val MAX_LINES = "inline_suggestion_max_lines"
    const val MANUAL_SHORTCUT = "inline_suggestion_manual_shortcut"
    const val SHOW_TOOLBAR_BUTTON = "inline_suggestion_toolbar_button"

    // Defaults (match global constraints)
    const val DEFAULT_ENABLED = true
    const val DEFAULT_AUTO_TRIGGER = true
    const val DEFAULT_CHAR_THRESHOLD = 3
    const val DEFAULT_DEBOUNCE_MS = 800
    const val DEFAULT_MAX_LINES = 5
    const val DEFAULT_MANUAL_SHORTCUT = "Ctrl+Space"
    const val DEFAULT_SHOW_TOOLBAR_BUTTON = true

    // Accessors
    var enabled: Boolean
        get() = prefManager.getBoolean(ENABLED, DEFAULT_ENABLED)
        set(value) {
            prefManager.putBoolean(ENABLED, value)
        }

    var autoTrigger: Boolean
        get() = prefManager.getBoolean(AUTO_TRIGGER, DEFAULT_AUTO_TRIGGER)
        set(value) {
            prefManager.putBoolean(AUTO_TRIGGER, value)
        }

    var charThreshold: Int
        get() = prefManager.getInt(CHAR_THRESHOLD, DEFAULT_CHAR_THRESHOLD)
        set(value) {
            prefManager.putInt(CHAR_THRESHOLD, value.coerceIn(2, 5))
        }

    var debounceMs: Int
        get() = prefManager.getInt(DEBOUNCE_MS, DEFAULT_DEBOUNCE_MS)
        set(value) {
            prefManager.putInt(DEBOUNCE_MS, value.coerceIn(100, 1000))
        }

    var maxLines: Int
        get() = prefManager.getInt(MAX_LINES, DEFAULT_MAX_LINES)
        set(value) {
            prefManager.putInt(MAX_LINES, value.coerceIn(1, 10))
        }

    var manualShortcut: String
        get() = prefManager.getString(MANUAL_SHORTCUT, DEFAULT_MANUAL_SHORTCUT) ?: DEFAULT_MANUAL_SHORTCUT
        set(value) {
            prefManager.putString(MANUAL_SHORTCUT, value)
        }

    var showToolbarButton: Boolean
        get() = prefManager.getBoolean(SHOW_TOOLBAR_BUTTON, DEFAULT_SHOW_TOOLBAR_BUTTON)
        set(value) {
            prefManager.putBoolean(SHOW_TOOLBAR_BUTTON, value)
        }
}
