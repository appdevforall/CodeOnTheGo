package com.itsaky.androidide.repositories

import android.net.Uri
import com.itsaky.androidide.plugins.PluginInfo
import java.io.File

/**
 * Repository interface for plugin operations
 * Defines the contract for plugin data operations
 */
interface PluginRepository {

    /**
     * Get all available plugins
     */
    suspend fun getAllPlugins(): Result<List<PluginInfo>>

    /**
     * Enable a plugin by ID
     */
    suspend fun enablePlugin(pluginId: String): Result<Boolean>

    /**
     * Disable a plugin by ID
     */
    suspend fun disablePlugin(pluginId: String): Result<Boolean>

    /**
     * Uninstall a plugin by ID
     */
    suspend fun uninstallPlugin(pluginId: String): Result<Boolean>

    /**
     * Install a plugin from file
     */
    suspend fun installPluginFromFile(pluginFile: File): Result<Unit>

    /**
     * Reload all plugins
     */
    suspend fun reloadPlugins(): Result<Unit>

    /**
     * Check if plugin manager is available
     */
    fun isPluginManagerAvailable(): Boolean
}