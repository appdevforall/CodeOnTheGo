package com.itsaky.androidide.plugins.extensions

import androidx.fragment.app.Fragment
import com.itsaky.androidide.plugins.IPlugin

/**
 * Interface for plugins that want to contribute tabs to the main editor tab bar.
 * This allows plugins to add their own UI directly alongside code editor tabs.
 */
interface EditorTabExtension : IPlugin {

    /**
     * Provide tabs for the main editor tab bar.
     * These tabs will appear alongside file editor tabs.
     *
     * @return List of editor tabs to display in the main tab bar
     */
    fun getMainEditorTabs(): List<EditorTabItem> = emptyList()

    /**
     * Called when one of the plugin's tabs is selected.
     *
     * @param tabId The ID of the selected tab
     * @param fragment The fragment instance for the tab
     */
    fun onEditorTabSelected(tabId: String, fragment: Fragment) {}

    /**
     * Called when one of the plugin's tabs is closed.
     *
     * @param tabId The ID of the closed tab
     */
    fun onEditorTabClosed(tabId: String) {}

    /**
     * Called when the plugin's tab is about to be closed.
     * Return false to prevent the tab from being closed.
     *
     * @param tabId The ID of the tab being closed
     * @return true to allow closing, false to prevent it
     */
    fun canCloseEditorTab(tabId: String): Boolean = true
}

/**
 * Represents a tab that can be added to the main editor tab bar.
 */
data class EditorTabItem(
    /**
     * Unique identifier for this tab. Should be unique across all plugins.
     */
    val id: String,

    /**
     * Display title for the tab.
     */
    val title: String,

    /**
     * Optional icon resource ID for the tab.
     */
    val icon: Int? = null,

    /**
     * Factory function to create the fragment for this tab.
     * Called when the tab needs to be displayed.
     */
    val fragmentFactory: () -> Fragment,

    /**
     * Whether this tab can be closed by the user.
     */
    val isCloseable: Boolean = true,

    /**
     * Whether this tab should persist across app restarts.
     * If true, the tab will be automatically restored when the app starts.
     */
    val isPersistent: Boolean = false,

    /**
     * Order/priority for tab positioning.
     * Lower values appear first. Plugin tabs are ordered among themselves.
     * File tabs always come before plugin tabs.
     */
    val order: Int = 0,

    /**
     * Whether the tab is currently enabled.
     */
    val isEnabled: Boolean = true,

    /**
     * Whether the tab is currently visible.
     */
    val isVisible: Boolean = true,

    /**
     * Optional tooltip text for the tab.
     */
    val tooltip: String? = null
)