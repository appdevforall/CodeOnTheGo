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

package com.itsaky.androidide.preferences

import android.content.Context
import android.content.ContextWrapper
import androidx.preference.Preference
import com.itsaky.androidide.app.EdgeToEdgeIDEActivity
import com.itsaky.androidide.resources.R
import com.itsaky.androidide.idetooltips.TooltipTag.PREFS_SECURITY
import com.itsaky.androidide.preferences.internal.SecurityPreferences
import kotlinx.parcelize.Parcelize

@Parcelize
class SecurityPreferencesScreen(
	override val key: String = "idepref_security",
	override val title: Int = R.string.idepref_security_title,
	override val summary: Int? = R.string.idepref_security_summary,
	override val children: List<IPreference> = mutableListOf(),
	override val tooltipTag: String = PREFS_SECURITY,
) : IPreferenceScreen() {

	init {
		addPreference(EmergencyHidingConfig())
	}
}

@Parcelize
class EmergencyHidingConfig(
	override val key: String = "idepref_security_emergency",
	override val title: Int = R.string.idepref_security_emergency_group,
	override val children: List<IPreference> = mutableListOf(),
) : IPreferenceGroup() {

	init {
		addPreference(InstantHide())
	}
}

@Parcelize
class InstantHide(
	override val key: String = SecurityPreferences.INSTANT_HIDE,
	override val title: Int = R.string.idepref_security_instantHide_title,
	override val summary: Int? = R.string.idepref_security_instantHide_summary,
) : SwitchPreference() {

	override fun onCreatePreference(context: Context): Preference {
		val pref = super.onCreatePreference(context) as androidx.preference.SwitchPreference
		pref.isChecked = SecurityPreferences.instantHideEnabled
		return pref
	}

	override fun onPreferenceChanged(preference: Preference, newValue: Any?): Boolean {
		SecurityPreferences.instantHideEnabled =
			newValue as Boolean? ?: SecurityPreferences.instantHideEnabled
		// Apply the FLAG_SECURE policy to this already-resumed Settings window now: the armed-state
		// contract is that screenshots/Recents are masked the moment Instant Hide is on, but
		// applyScreenSecurity() otherwise only runs on the next onResume, leaving a gap right after
		// arming. Also clears the flag immediately when the user turns it back off.
		preference.context.findIDEActivity()?.applyInstantHideScreenSecurity()
		return true
	}
}

/**
 * The hosting [EdgeToEdgeIDEActivity], or null. A preference's context is whatever the host passed
 * when the row was built -- today the Activity itself, but unwrapping the [ContextWrapper] chain
 * keeps this correct if a themed wrapper is ever interposed, since a missed call would silently
 * leave the window screenshotable.
 */
private fun Context.findIDEActivity(): EdgeToEdgeIDEActivity? {
	var ctx: Context? = this
	while (ctx is ContextWrapper) {
		if (ctx is EdgeToEdgeIDEActivity) return ctx
		ctx = ctx.baseContext
	}
	return null
}
