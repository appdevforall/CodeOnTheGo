

package com.itsaky.androidide.plugins.extensions

import androidx.fragment.app.Fragment
import com.itsaky.androidide.plugins.IPlugin

/**
 * Interface for plugins that extend the IDE's user interface.
 * Provides hooks for adding custom UI elements to various parts of the IDE.
 */
interface UIExtension : IPlugin {

    /**
     * Provide menu items for the main menu bar.
     * @return List of menu items to add to the main menu
     */
    fun getMainMenuItems(): List<MenuItem> = emptyList()

    /**
     * Provide context menu items based on the current context.
     * @param context Information about where the context menu was triggered
     * @return List of context-specific menu items
     */
    fun getContextMenuItems(context: ContextMenuContext): List<MenuItem> = emptyList()

    /**
     * Provide tabs for the editor's bottom sheet panel.
     * @return List of tabs to display in the editor bottom sheet
     */
    fun getEditorTabs(): List<TabItem> = emptyList()

    /**
     * Provide items for the side navigation drawer.
     * @return List of navigation items for the side menu
     */
    fun getSideMenuItems(): List<NavigationItem> = emptyList()

    /**
     * Provide toolbar actions for the editor.
     * @return List of toolbar actions
     */
    fun getToolbarActions(): List<ToolbarAction> = emptyList()

    /**
     * Provide floating action button (FAB) actions.
     * @return List of FAB actions for different screens
     */
    fun getFabActions(): List<FabAction> = emptyList()
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

data class NavigationItem(
    val id: String,
    val title: String,
    val icon: Int? = null,
    val isEnabled: Boolean = true,
    val isVisible: Boolean = true,
    val group: String? = null,
    val order: Int = 0,
    val action: () -> Unit
)

data class ToolbarAction(
    val id: String,
    val title: String,
    val icon: Int? = null,
    val showAsAction: ShowAsAction = ShowAsAction.IF_ROOM,
    val isEnabled: Boolean = true,
    val isVisible: Boolean = true,
    val order: Int = 0,
    val action: () -> Unit
)

enum class ShowAsAction {
    ALWAYS,
    IF_ROOM,
    NEVER,
    WITH_TEXT,
    COLLAPSE_ACTION_VIEW
}

data class FabAction(
    val id: String,
    val screenId: String,
    val icon: Int,
    val contentDescription: String,
    val isEnabled: Boolean = true,
    val isVisible: Boolean = true,
    val action: () -> Unit
)