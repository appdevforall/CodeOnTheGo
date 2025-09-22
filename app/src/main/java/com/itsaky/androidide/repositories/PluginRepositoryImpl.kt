package com.itsaky.androidide.repositories

import android.util.Log
import com.itsaky.androidide.plugins.PluginInfo
import com.itsaky.androidide.plugins.manager.core.PluginManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Implementation of PluginRepository
 * Handles all plugin-related data operations
 */
class PluginRepositoryImpl(
    private val pluginManagerProvider: () -> PluginManager?,
    private val pluginsDir: File
) : PluginRepository {

    private companion object {
        private const val TAG = "PluginRepository"
    }

    private val pluginManager: PluginManager?
        get() = pluginManagerProvider()

    override suspend fun getAllPlugins(): Result<List<PluginInfo>> = withContext(Dispatchers.IO) {
        runCatching {
            val manager = pluginManager
                ?: throw IllegalStateException("Plugin system not available")
            manager.getAllPlugins()
        }.onFailure { exception ->
            Log.e(TAG, "Failed to get all plugins", exception)
        }
    }

    override suspend fun enablePlugin(pluginId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val manager = pluginManager
                ?: throw IllegalStateException("Plugin system not available")
            val result = manager.enablePlugin(pluginId)
            result
        }.onFailure { exception ->
            Log.e(TAG, "Failed to enable plugin: $pluginId", exception)
        }
    }

    override suspend fun disablePlugin(pluginId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val manager = pluginManager
                ?: throw IllegalStateException("Plugin system not available")
            val result = manager.disablePlugin(pluginId)
            result
        }.onFailure { exception ->
            Log.e(TAG, "Failed to disable plugin: $pluginId", exception)
        }
    }

    override suspend fun uninstallPlugin(pluginId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val manager = pluginManager
                ?: throw IllegalStateException("Plugin system not available")

            Log.d(TAG, "Uninstalling plugin: $pluginId")
            val result = manager.uninstallPlugin(pluginId)
            result
        }.onFailure { exception ->
            Log.e(TAG, "Failed to uninstall plugin: $pluginId", exception)
        }
    }

    override suspend fun installPluginFromFile(pluginFile: File): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val manager = pluginManager
                ?: throw IllegalStateException("Plugin system not available")

            // Load plugin with metadata to validate and get plugin info
            val loadResult = manager.loadPluginWithMetadata(pluginFile)

            if (loadResult.isSuccess) {
                val (_, metadata) = loadResult.getOrNull() ?: (null to null)
                val pluginId = metadata?.id

                if (pluginId != null) {
                    // Uninstall existing version if it exists
                    try {
                        manager.uninstallPlugin(pluginId)
                        Log.d(TAG, "Uninstalled existing version of plugin: $pluginId")
                    } catch (e: Exception) {
                        Log.w(TAG, "Error uninstalling existing plugin: ${e.message}")
                    }

                    // Move file to plugins directory with proper naming convention
                    val fileExtension = if (pluginFile.name.endsWith(".cgp")) ".cgp" else ".apk"
                    val finalFileName = "${pluginId}$fileExtension"

                    // Ensure plugins directory exists
                    if (!pluginsDir.exists()) {
                        pluginsDir.mkdirs()
                    }

                    val finalFile = File(pluginsDir, finalFileName)

                    // Copy the file to the plugins directory
                    try {
                        pluginFile.copyTo(finalFile, overwrite = true)
                        Log.d(TAG, "Plugin file copied to: ${finalFile.absolutePath}")

                        // Delete the temporary file
                        pluginFile.delete()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to copy plugin file to plugins directory", e)
                        throw e
                    }
                }

                // Reload plugins to pick up the new installation
                manager.loadPlugins()
            } else {
                if (pluginFile.exists()) {
                    pluginFile.delete()
                }
                throw Exception("Failed to load plugin: ${loadResult.exceptionOrNull()?.message}")
            }
        }.onFailure { exception ->
            Log.e(TAG, "Failed to install plugin from file: ${pluginFile.absolutePath}", exception)
        }
    }

    override suspend fun reloadPlugins(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val manager = pluginManager
                ?: throw IllegalStateException("Plugin system not available")

            manager.loadPlugins()
        }.onFailure { exception ->
            Log.e(TAG, "Failed to reload plugins", exception)
        }
    }

    override fun isPluginManagerAvailable(): Boolean {
        val available = pluginManager != null
        return available
    }
}