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

package com.itsaky.androidide.plugins.manager

import android.content.Context
import com.itsaky.androidide.plugins.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class PluginManager private constructor(
    private val context: Context,
    private val eventBus: Any, // EventBus reference to avoid direct dependency
    private val logger: PluginLogger
) {
    
    companion object {
        @Volatile
        private var INSTANCE: PluginManager? = null
        
        fun getInstance(context: Context, eventBus: Any, logger: PluginLogger): PluginManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PluginManager(context, eventBus, logger).also { INSTANCE = it }
            }
        }
        
        /**
         * Get the already initialized instance, or null if not yet initialized
         */
        fun getInstance(): PluginManager? {
            return INSTANCE
        }
    }
    
    private val loadedPlugins = ConcurrentHashMap<String, LoadedPlugin>()
    private val pluginRegistry = PluginRegistry(context)
    private val securityManager = PluginSecurityManager()
    private val serviceRegistry = ServiceRegistryImpl()
    
    private val pluginsDir = File(context.filesDir, "plugins")
    
    init {
        if (!pluginsDir.exists()) {
            pluginsDir.mkdirs()
        }
    }
    
    fun loadPlugins() {
        logger.info("Loading plugins from directory: ${pluginsDir.absolutePath}")
        
        val pluginFiles = pluginsDir.listFiles { file ->
            file.isFile && file.name.endsWith(".jar")
        } ?: return
        
        for (pluginFile in pluginFiles) {
            try {
                loadPlugin(pluginFile)
            } catch (e: Exception) {
                logger.error("Failed to load plugin from ${pluginFile.name}", e)
            }
        }
        
        logger.info("Loaded ${loadedPlugins.size} plugins")
    }
    
    fun loadPlugin(pluginFile: File): Result<IPlugin> {
        return try {
            logger.debug("Loading plugin from: ${pluginFile.absolutePath}")
            
            val manifest = PluginManifestParser.parseFromJar(pluginFile)
            if (manifest == null) {
                return Result.failure(IllegalArgumentException("Plugin manifest not found or invalid"))
            }
            
            if (!securityManager.validatePlugin(pluginFile, manifest)) {
                return Result.failure(SecurityException("Plugin failed security validation"))
            }
            
            val permissions = try {
                manifest.permissions.mapNotNull { permissionStr ->
                    try {
                        // Convert string to enum by replacing dots with underscores and making uppercase
                        val enumName = permissionStr.uppercase().replace(".", "_")
                        PluginPermission.valueOf(enumName)
                    } catch (e: IllegalArgumentException) {
                        logger.warn("Invalid permission in plugin manifest: $permissionStr")
                        null
                    }
                }.toSet()
            } catch (e: Exception) {
                logger.warn("Error processing permissions: ${e.message}")
                emptySet<PluginPermission>()
            }
            
            val classLoader = PluginClassLoader(
                pluginFile,
                this::class.java.classLoader!!,
                permissions
            )
            
            val pluginClass = classLoader.loadClass(manifest.mainClass)
            val plugin = pluginClass.getDeclaredConstructor().newInstance() as IPlugin
            
            val pluginContext = createPluginContext(manifest.id, classLoader)
            
            if (plugin.initialize(pluginContext)) {
                val loadedPlugin = LoadedPlugin(plugin, manifest, classLoader, pluginContext)
                loadedPlugins[manifest.id] = loadedPlugin
                
                logger.info("Successfully loaded plugin: ${manifest.name} (${manifest.id})")
                Result.success(plugin)
            } else {
                Result.failure(RuntimeException("Plugin initialization failed"))
            }
        } catch (e: Exception) {
            logger.error("Failed to load plugin from ${pluginFile.name}", e)
            Result.failure(e)
        }
    }
    
    fun unloadPlugin(pluginId: String): Boolean {
        val loadedPlugin = loadedPlugins.remove(pluginId) ?: return false
        
        try {
            loadedPlugin.plugin.deactivate()
            loadedPlugin.plugin.dispose()
            logger.info("Unloaded plugin: $pluginId")
            return true
        } catch (e: Exception) {
            logger.error("Failed to unload plugin: $pluginId", e)
            return false
        }
    }
    
    fun getPlugin(pluginId: String): IPlugin? {
        return loadedPlugins[pluginId]?.plugin
    }
    
    fun getAllPlugins(): List<PluginInfo> {
        return loadedPlugins.values.map { loadedPlugin ->
            PluginInfo(
                metadata = loadedPlugin.plugin.metadata,
                isEnabled = true, // TODO: implement enable/disable state
                isLoaded = true
            )
        }
    }
    
    /**
     * Get all loaded plugin instances for UI integration
     */
    fun getAllPluginInstances(): List<IPlugin> {
        return loadedPlugins.values.map { it.plugin }
    }
    
    fun enablePlugin(pluginId: String): Boolean {
        val loadedPlugin = loadedPlugins[pluginId] ?: return false
        return try {
            loadedPlugin.plugin.activate()
            logger.info("Enabled plugin: $pluginId")
            true
        } catch (e: Exception) {
            logger.error("Failed to enable plugin: $pluginId", e)
            false
        }
    }
    
    fun disablePlugin(pluginId: String): Boolean {
        val loadedPlugin = loadedPlugins[pluginId] ?: return false
        return try {
            loadedPlugin.plugin.deactivate()
            logger.info("Disabled plugin: $pluginId")
            true
        } catch (e: Exception) {
            logger.error("Failed to disable plugin: $pluginId", e)
            false
        }
    }
    
    fun getServiceRegistry(): ServiceRegistry = serviceRegistry
    
    private fun createPluginContext(pluginId: String, classLoader: ClassLoader): PluginContext {
        return PluginContextImpl(
            androidContext = context,
            services = serviceRegistry,
            eventBus = eventBus,
            logger = PluginLoggerImpl(pluginId, logger),
            resources = ResourceManagerImpl(pluginId, pluginsDir, classLoader),
            pluginId = pluginId
        )
    }
}

data class LoadedPlugin(
    val plugin: IPlugin,
    val manifest: PluginManifest,
    val classLoader: ClassLoader,
    val context: PluginContext
)