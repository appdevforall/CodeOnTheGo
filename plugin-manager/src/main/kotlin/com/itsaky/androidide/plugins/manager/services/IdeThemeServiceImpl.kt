package com.itsaky.androidide.plugins.manager.services

import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import com.itsaky.androidide.plugins.services.IdeThemeService
import com.itsaky.androidide.plugins.services.ThemeChangeListener
import com.itsaky.androidide.utils.isSystemInDarkMode

class IdeThemeServiceImpl(
    private val context: Context
) : IdeThemeService {

    private val listeners = mutableListOf<ThemeChangeListener>()
    private var lastKnownDarkMode: Boolean = false

    private val configCallback = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            val isDark = isDarkMode()
            if (isDark != lastKnownDarkMode) {
                lastKnownDarkMode = isDark
                listeners.forEach { it.onThemeChanged(isDark) }
            }
        }

        override fun onLowMemory() {}
    }

    init {
        lastKnownDarkMode = isDarkMode()
        context.registerComponentCallbacks(configCallback)
    }

    override fun isDarkMode(): Boolean {
        return when (AppCompatDelegate.getDefaultNightMode()) {
            AppCompatDelegate.MODE_NIGHT_YES -> true
            AppCompatDelegate.MODE_NIGHT_NO -> false
            else -> context.isSystemInDarkMode()
        }
    }

    override fun addThemeChangeListener(listener: ThemeChangeListener) {
        listeners.add(listener)
    }

    override fun removeThemeChangeListener(listener: ThemeChangeListener) {
        listeners.remove(listener)
    }

    fun dispose() {
        context.unregisterComponentCallbacks(configCallback)
        listeners.clear()
    }
}
