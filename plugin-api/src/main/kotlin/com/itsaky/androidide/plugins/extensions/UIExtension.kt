

package com.itsaky.androidide.plugins.extensions

import androidx.fragment.app.Fragment
import com.itsaky.androidide.plugins.IPlugin

interface UIExtension : IPlugin {
    fun contributeToMainMenu(): List<MenuItem>
    fun contributeToContextMenu(context: ContextMenuContext): List<MenuItem>
    fun contributeToEditorBottomSheet(): List<TabItem>
}

data class MenuItem(
    val id: String,
    val title: String,
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

data class TabItem(
    val id: String,
    val title: String,
    val fragmentFactory: () -> Fragment,
    val isEnabled: Boolean = true,
    val isVisible: Boolean = true,
    val order: Int = 0
)