package com.itsvks.layouteditor

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.color.DynamicColors
import com.itsvks.layouteditor.managers.PreferencesManager

class LayoutEditor : Application() {
  override fun onCreate() {
    super.onCreate()
    instance = this
    AppCompatDelegate.setDefaultNightMode(PreferencesManager.currentTheme)
    if (PreferencesManager.isApplyDynamicColors && DynamicColors.isDynamicColorAvailable()) {
      DynamicColors.applyToActivitiesIfAvailable(this)
    }
  }

  val context: Context
    get() = instance!!.applicationContext
  val isAtLeastTiramisu: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

  fun updateTheme(nightMode: Int, activity: Activity) {
    AppCompatDelegate.setDefaultNightMode(nightMode)
    activity.recreate()
  }

  companion object {
    var instance: LayoutEditor? = null
      private set
  }
}
