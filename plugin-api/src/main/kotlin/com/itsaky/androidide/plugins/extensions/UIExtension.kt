/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.plugins.extensions

import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.itsaky.androidide.plugins.IPlugin

interface UIExtension : IPlugin {
    fun createToolWindow(): ToolWindow?
    fun contributeToMainMenu(): List<MenuItem>
    fun contributeToContextMenu(context: ContextMenuContext): List<MenuItem>
    fun provideTheme(): Theme?
    fun createStatusBarWidget(): StatusBarWidget?
}

interface ToolWindow {
    val id: String
    val title: String
    val icon: Drawable?
    val isCloseable: Boolean get() = true
    val defaultPosition: ToolWindowPosition get() = ToolWindowPosition.RIGHT
    
    fun createContent(container: ViewGroup): View
    fun createFragment(): Fragment?
    fun onShow() {}
    fun onHide() {}
    fun onFocus() {}
    fun onLostFocus() {}
}

enum class ToolWindowPosition {
    LEFT,
    RIGHT,
    BOTTOM,
    TOP
}

data class MenuItem(
    val id: String,
    val title: String,
    val icon: Drawable?,
    val isEnabled: Boolean = true,
    val isVisible: Boolean = true,
    val shortcut: String? = null,
    val subItems: List<MenuItem> = emptyList(),
    val action: () -> Unit
)

data class ContextMenuContext(
    val file: java.io.File?,
    val selectedText: String?,
    val cursorPosition: Int?,
    val additionalData: Map<String, Any> = emptyMap()
)

data class Theme(
    val id: String,
    val name: String,
    val isDark: Boolean,
    val colors: Map<String, Int>,
    val styles: Map<String, Any>
)

interface StatusBarWidget {
    val id: String
    val priority: Int get() = 0
    
    fun createView(container: ViewGroup): View
    fun update(data: Map<String, Any>) {}
    fun onClick() {}
}