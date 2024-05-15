package com.itsvks.layouteditor.fragments.ui

import android.os.Bundle
import android.os.Process
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.itsvks.layouteditor.R
import com.itsvks.layouteditor.managers.PreferencesManager
import com.itsvks.layouteditor.managers.SharedPreferencesKeys
import kotlin.system.exitProcess

class PreferencesFragment : PreferenceFragmentCompat() {

  private val themes by lazy {
    arrayOf(
      getString(R.string.theme_auto),
      getString(R.string.theme_dark),
      getString(R.string.theme_light)
    )
  }

  private val themeValues by lazy { arrayOf("Auto", "Dark", "Light") }

  override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
    setPreferencesFromResource(R.xml.preference, rootKey)
    setDynamicColorsChangeWarning(findPreference(SharedPreferencesKeys.KEY_DYNAMIC_COLORS))

    findPreference<Preference>(SharedPreferencesKeys.KEY_APP_THEME)?.onPreferenceClickListener =
      Preference.OnPreferenceClickListener {
        val selectedThemeValue =
          PreferencesManager.prefs.getString(SharedPreferencesKeys.KEY_APP_THEME, "Auto")
        MaterialAlertDialogBuilder(requireContext())
          .setTitle(R.string.choose_theme)
          .setSingleChoiceItems(themes, themeValues.indexOf(selectedThemeValue)) { d, w ->
            PreferencesManager.prefs.edit()
              .putString(SharedPreferencesKeys.KEY_APP_THEME, themeValues[w]).apply()
            AppCompatDelegate.setDefaultNightMode(PreferencesManager.currentTheme)
            d.dismiss()
          }
          .setPositiveButton(R.string.cancel, null)
          .show()
        true
      }
  }

  private fun setDynamicColorsChangeWarning(preference: SwitchPreferenceCompat?) {
    preference?.onPreferenceChangeListener =
      Preference.OnPreferenceChangeListener { _, _ ->
        MaterialAlertDialogBuilder(requireContext())
          .setTitle(R.string.note)
          .setMessage(R.string.msg_dynamic_colors_dialog)
          .setCancelable(false)
          .setNegativeButton(R.string.cancel) { d, _ ->
            preference?.sharedPreferences?.edit()
              ?.putBoolean(preference.key, !PreferencesManager.isApplyDynamicColors)?.apply()
            preference?.isChecked = PreferencesManager.isApplyDynamicColors
            d.cancel()
          }
          .setPositiveButton(R.string.okay) { _, _ ->
            requireActivity().finishAffinity()
            Process.killProcess(Process.myPid())
            exitProcess(0)
          }
          .show()
        true
      }
  }
}
