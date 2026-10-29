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

package com.itsaky.androidide.security

import android.content.Context
import com.itsaky.androidide.preferences.internal.SecurityPreferences
import com.itsaky.androidide.resources.R as ResR

/**
 * Instant Hide's two flags, read through here rather than from [SecurityPreferences] directly so a
 * test can stand in for the preferences, which need the running application.
 */
internal interface HideState {
	/** The user has armed Instant Hide. */
	val armed: Boolean

	/** The user has armed Instant Wipe (ADFA-6510), which arms separately. */
	val wipeArmed: Boolean

	/** The disguise is engaged. */
	var hidden: Boolean

	/**
	 * Instant Wipe (ADFA-6510) has neutralized this install, or its markers were stripped while the
	 * stealth alias stayed (the tamper fingerprint): it is the disguise for good, whatever [hidden] says.
	 */
	fun neutralized(context: Context): Boolean
}

internal object PreferencesHideState : HideState {
	override val armed: Boolean get() = SecurityPreferences.instantHideEnabled

	override val wipeArmed: Boolean get() = SecurityPreferences.instantWipeEnabled

	override var hidden: Boolean
		get() = SecurityPreferences.isHiddenStateActive
		set(value) {
			SecurityPreferences.isHiddenStateActive = value
		}

	override fun neutralized(context: Context): Boolean =
		NeutralizationMarker.isWiped(context) ||
			NeutralizationMarker.isTampered(context, context.getString(ResR.string.stealth_app_name))
}
