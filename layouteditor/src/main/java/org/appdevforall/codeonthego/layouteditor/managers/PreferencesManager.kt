package org.appdevforall.codeonthego.layouteditor.managers

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.PreferenceManager

class PreferencesManager private constructor(context: Context) {
	private val appContext = context.applicationContext

  val prefs: SharedPreferences by lazy {
		PreferenceManager.getDefaultSharedPreferences(appContext)
	}

	companion object {
		@Volatile
		private var INSTANCE: PreferencesManager? = null
		fun getInstance(context: Context): PreferencesManager {
			return INSTANCE ?: synchronized(this) {
				INSTANCE ?: PreferencesManager(context).also { INSTANCE = it }
			}
		}

		operator fun invoke(context: Context): PreferencesManager {
			return getInstance(context)
		}
	}

  val isEnableVibration: Boolean
    get() = prefs.getBoolean(SharedPreferencesKeys.KEY_VIBRATION, false)

  val isShowStroke: Boolean
    get() = prefs.getBoolean(SharedPreferencesKeys.KEY_TOGGLE_STROKE, true)

  val isApplyDynamicColors: Boolean
    get() = prefs.getBoolean(SharedPreferencesKeys.KEY_DYNAMIC_COLORS, false)

  val currentTheme: Int
    get() = when (prefs.getString(SharedPreferencesKeys.KEY_APP_THEME, "Auto")) {
      "Light" -> AppCompatDelegate.MODE_NIGHT_NO
      "Dark" -> AppCompatDelegate.MODE_NIGHT_YES
      else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }
}
