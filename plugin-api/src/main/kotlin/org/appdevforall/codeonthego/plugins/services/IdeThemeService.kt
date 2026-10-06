package org.appdevforall.codeonthego.plugins.services

interface IdeThemeService {

    fun isDarkMode(): Boolean

    fun addThemeChangeListener(listener: ThemeChangeListener)

    fun removeThemeChangeListener(listener: ThemeChangeListener)
}

interface ThemeChangeListener {

    fun onThemeChanged(isDarkMode: Boolean)
}
