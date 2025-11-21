package org.appdevforall.codeonthego.layouteditor

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.color.DynamicColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import org.appdevforall.codeonthego.layouteditor.editor.DesignEditor
import org.appdevforall.codeonthego.layouteditor.managers.PreferencesManager

class LayoutEditor : Application() {

  private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private lateinit var prefManager: PreferencesManager

  override fun onCreate() {
    super.onCreate()
    instance = this
    prefManager = PreferencesManager(this.context)
    AppCompatDelegate.setDefaultNightMode(prefManager.currentTheme)
    if (prefManager.isApplyDynamicColors && DynamicColors.isDynamicColorAvailable()) {
      DynamicColors.applyToActivitiesIfAvailable(this)
    }

    applicationScope.launch {
      DesignEditor.preload(context)
    }
  }

  override fun onTerminate() {
    super.onTerminate()
    applicationScope.coroutineContext.cancelChildren()
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
