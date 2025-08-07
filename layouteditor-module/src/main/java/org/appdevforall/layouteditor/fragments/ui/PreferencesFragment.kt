package com.itsvks.layouteditor.fragments.ui

import android.os.Bundle
import android.os.Process
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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

  private var preferencesManager: PreferencesManager? = null

  private val themes by lazy {
    arrayOf(
      getString(R.string.theme_auto),
      getString(R.string.theme_dark),
      getString(R.string.theme_light)
    )
  }

  override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
    savedInstanceState: Bundle?): View {
    preferencesManager = this.context?.let { PreferencesManager(it) }
    return super.onCreateView(inflater, container, savedInstanceState)
  }

  private val themeValues by lazy { arrayOf("Auto", "Dark", "Light") }

  override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
    setPreferencesFromResource(R.xml.preference, rootKey)
    setDynamicColorsChangeWarning(findPreference(SharedPreferencesKeys.KEY_DYNAMIC_COLORS))

    findPreference<Preference>(SharedPreferencesKeys.KEY_APP_THEME)?.onPreferenceClickListener =
      Preference.OnPreferenceClickListener {
        val selectedThemeValue =
          preferencesManager?.prefs?.getString(SharedPreferencesKeys.KEY_APP_THEME, "Auto")
        MaterialAlertDialogBuilder(requireContext())
          .setTitle(R.string.choose_theme)
          .setSingleChoiceItems(themes, themeValues.indexOf(selectedThemeValue)) { d, w ->
            preferencesManager?.prefs?.edit()
              ?.putString(SharedPreferencesKeys.KEY_APP_THEME, themeValues[w])?.apply()
            preferencesManager?.currentTheme?.let { manager ->
              AppCompatDelegate.setDefaultNightMode(manager)
            }
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
              ?.putBoolean(preference.key, preferencesManager?.isApplyDynamicColors == true)
              ?.apply()
            preference?.isChecked = preferencesManager?.isApplyDynamicColors == true
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
