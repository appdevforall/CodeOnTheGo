package com.itsaky.androidide.plugins.services

/**
 * Service for managing plugin editor tabs integration.
 * This service allows plugins to interact with the main editor tab system.
 */
interface IdeEditorTabService {

    /**
     * Check if a tab with the given ID is currently registered as a plugin tab.
     *
     * @param tabId The tab ID to check
     * @return true if it's a plugin tab, false otherwise
     */
    fun isPluginTab(tabId: String): Boolean

    /**
     * Focus/select a plugin tab with the given ID.
     *
     * @param tabId The tab ID to select
     * @return true if the tab was found and selected, false otherwise
     */
    fun selectPluginTab(tabId: String): Boolean

    /**
     * Get all currently registered plugin tab IDs.
     *
     * @return List of plugin tab IDs
     */
    fun getAllPluginTabIds(): List<String>

    /**
     * Check if the editor tab system is currently available.
     *
     * @return true if the tab system is available, false otherwise
     */
    fun isTabSystemAvailable(): Boolean
}