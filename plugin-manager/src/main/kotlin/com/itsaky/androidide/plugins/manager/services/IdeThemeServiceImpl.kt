package com.itsaky.androidide.plugins.manager.services

import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import com.itsaky.androidide.plugins.services.IdeThemeService
import com.itsaky.androidide.plugins.services.ThemeChangeListener
import com.itsaky.androidide.utils.isSystemInDarkMode

class IdeThemeServiceImpl(
    private val context: Context
) : IdeThemeService {

    private val listeners = mutableListOf<ThemeChangeListener>()
    private var lastKnownDarkMode: Boolean = context.isSystemInDarkMode()

    private val configCallback = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            val isDark = (newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            if (isDark != lastKnownDarkMode) {
                lastKnownDarkMode = isDark
                listeners.forEach { it.onThemeChanged(isDark) }
            }
        }

        override fun onLowMemory() {}
    }

    init {
        context.registerComponentCallbacks(configCallback)
    }

    override fun isDarkMode(): Boolean {
        return context.isSystemInDarkMode()
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
