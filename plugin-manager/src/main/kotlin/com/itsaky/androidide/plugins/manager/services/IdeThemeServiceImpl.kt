package com.itsaky.androidide.plugins.manager.services

import android.content.Context
import com.itsaky.androidide.plugins.services.IdeThemeService
import com.itsaky.androidide.plugins.services.ThemeChangeListener
import com.itsaky.androidide.utils.isSystemInDarkMode

class IdeThemeServiceImpl(
    private val context: Context
) : IdeThemeService {

    private val listeners = mutableListOf<ThemeChangeListener>()

    override fun isDarkMode(): Boolean {
        return context.isSystemInDarkMode()
    }

    override fun addThemeChangeListener(listener: ThemeChangeListener) {
        listeners.add(listener)
    }

    override fun removeThemeChangeListener(listener: ThemeChangeListener) {
        listeners.remove(listener)
    }

    fun notifyThemeChanged() {
        val isDark = isDarkMode()
        listeners.forEach { it.onThemeChanged(isDark) }
    }
}
