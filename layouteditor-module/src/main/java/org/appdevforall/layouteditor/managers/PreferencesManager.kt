package com.itsvks.layouteditor.managers

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.PreferenceManager
import com.itsvks.layouteditor.LayoutEditor.Companion.instance

class PreferencesManager(val context: Context) {

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

  val prefs: SharedPreferences
    get() = PreferenceManager.getDefaultSharedPreferences(context)
}
