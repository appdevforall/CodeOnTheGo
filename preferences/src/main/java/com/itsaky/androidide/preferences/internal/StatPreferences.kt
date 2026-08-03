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

import android.content.Context
import android.content.SharedPreferences
import com.itsaky.androidide.app.BaseApplication

enum class TelemetryConsent {
	UNSET,
	GRANTED,
	DECLINED,
}

object StatPreferences {
	const val TELEMETRY_CONSENT = "ide.stats.telemetryConsent"

	private const val PREFS_FILE = "ide.stats"

	private var cachedPrefs: SharedPreferences? = null
	private var cachedPrefsApp: BaseApplication? = null

	private val prefs: SharedPreferences
		get() {
			val app = BaseApplication.baseInstance
			cachedPrefs?.takeIf { cachedPrefsApp === app }?.let { return it }
			return app
				.createDeviceProtectedStorageContext()
				.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
				.also {
					cachedPrefs = it
					cachedPrefsApp = app
				}
		}

	var telemetryConsent: TelemetryConsent
		get() =
			prefs
				.getString(TELEMETRY_CONSENT, null)
				?.let { stored -> TelemetryConsent.entries.firstOrNull { it.name == stored } }
				?: TelemetryConsent.UNSET
		set(value) {
			prefs.edit().putString(TELEMETRY_CONSENT, value.name).apply()
		}
}
