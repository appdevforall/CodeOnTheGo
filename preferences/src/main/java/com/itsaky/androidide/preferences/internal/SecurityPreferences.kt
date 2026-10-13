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
 * Preferences for the "Instant Hide" (ADFA-40) and "Instant Wipe" (ADFA-6510) emergency security
 * features.
 *
 * [instantHideEnabled] and [instantWipeEnabled] are the user-facing arm switches (both off by
 * default; Instant Wipe is independently armed). [isHiddenStateActive] is internal runtime state: it
 * records that the disguise is currently engaged, so the app re-enters the disguise on the next
 * launch (surviving restarts/reboots) until the user performs the exit gesture. All are backed by
 * shared preferences, which persist across restarts and clear on uninstall.
 */
@Suppress("MemberVisibilityCanBePrivate")
object SecurityPreferences {
	const val INSTANT_HIDE = "idepref_security_instantHide"
	const val INSTANT_WIPE = "idepref_security_instantWipe"
	const val IS_HIDDEN_STATE = "idepref_security_isHiddenStateActive"

	var instantHideEnabled: Boolean
		get() = prefManager.getBoolean(INSTANT_HIDE, false)
		set(value) {
			prefManager.putBoolean(INSTANT_HIDE, value)
		}

	var instantWipeEnabled: Boolean
		get() = prefManager.getBoolean(INSTANT_WIPE, false)
		set(value) {
			prefManager.putBoolean(INSTANT_WIPE, value)
		}

	var isHiddenStateActive: Boolean
		get() = prefManager.getBoolean(IS_HIDDEN_STATE, false)
		set(value) {
			prefManager.putBoolean(IS_HIDDEN_STATE, value)
		}

	/**
	 * Blanks all security state (post-destruct). Used by Instant Wipe: after the wipe the app reads as
	 * un-armed, which together with the stealth alias still being enabled is the tamper signature that
	 * re-triggers neutralization if someone clears app data. All flags default to false, so setting
	 * them false is equivalent to removing the keys.
	 */
	fun clearAll() {
		instantHideEnabled = false
		instantWipeEnabled = false
		isHiddenStateActive = false
	}
}
