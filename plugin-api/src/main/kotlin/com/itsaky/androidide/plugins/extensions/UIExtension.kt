

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

    /**
     * Provide the ids of editor-toolbar actions that should be hidden right now.
     * Queried each time the toolbar is rebuilt (on file switch, edit, save, etc.), so
     * the result may vary per file — e.g. return the built-in XML preview action's id
     * only while a Compose file is open. Determine the current file through your own
     * editor service, the same way [ToolbarAction.isVisibleProvider] does. The host
     * hides exactly the ids returned; there is no allow-list. Action ids are available
     * as constants on [ToolbarActionIds]. Returning an id that is not currently on the
     * toolbar is a no-op.
     *
     * @return Set of toolbar action ids to hide.
     */
    fun getHiddenToolbarActionIds(): Set<String> = emptySet()
}

data class MenuItem @JvmOverloads constructor(
    val id: String,
    val title: String,
    val isEnabled: Boolean = true,
    val isVisible: Boolean = true,
    val shortcut: String? = null,
    val subItems: List<MenuItem> = emptyList(),
    val action: () -> Unit,
    /**
     * Optional tooltip tag to look up under the plugin's tooltip category
     * (`plugin_<pluginId>`). When null, the IDE composes a tag using the
     * convention `<pluginId>.<id>`. Supplying the same tooltipTag on a
     * NavigationItem and a MenuItem lets a single PluginTooltipEntry serve
     * both the sidebar and the toolbar surfaces.
     */
    val tooltipTag: String? = null,
    val icon: Int? = null
) {
    var isEnabledProvider: (() -> Boolean)? = null
    var isVisibleProvider: (() -> Boolean)? = null
}

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
    val order: Int = 0,
    /**
     * Optional tooltip tag to look up under the plugin's tooltip category
     * (`plugin_<pluginId>`). When null, the IDE composes a tag using the
     * convention `<pluginId>.<id>` so plugins do not need to manually
     * namespace tags to avoid collisions across plugins. If the plugin's
     * id can't be resolved at registration time, the IDE falls back to a
     * generic placeholder tag under the IDE tooltip category. Mirrors the
     * tooltipTag fallback already on NavigationItem and MenuItem.
     */
    val tooltipTag: String? = null
)

data class NavigationItem(
    val id: String,
    val title: String,
    val icon: Int? = null,
    val isEnabled: Boolean = true,
    val isVisible: Boolean = true,
    val group: String? = null,
    val order: Int = 0,
    /**
     * Optional tooltip tag to look up under the plugin's tooltip category
     * (`plugin_<pluginId>`). When null, the IDE composes a tag using the
     * convention `<pluginId>.<id>` so plugins do not need to manually
     * namespace tags to avoid collisions across plugins.
     */
    val tooltipTag: String? = null,
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
) {
    /**
     * Optional callback to compute the enabled state dynamically at render time.
     * When null, the static [isEnabled] is used. Mirrors the [MenuItem] providers.
     */
    var isEnabledProvider: (() -> Boolean)? = null

    /**
     * Optional callback to compute the visible state dynamically at render time.
     * When null, the static [isVisible] is used. Unlike system toolbar actions
     * (which only grey out when disabled), a plugin toolbar action is fully removed
     * from the toolbar when this resolves to false.
     */
    var isVisibleProvider: (() -> Boolean)? = null
}

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
