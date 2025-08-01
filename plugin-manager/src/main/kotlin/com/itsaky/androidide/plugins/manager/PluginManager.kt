

package com.itsaky.androidide.plugins.manager

import android.app.Activity
import android.content.Context
import com.itsaky.androidide.plugins.*
import com.itsaky.androidide.plugins.services.IdeProjectService
import com.itsaky.androidide.plugins.services.IdeEditorService
import com.itsaky.androidide.plugins.services.IdeUIService
import com.itsaky.androidide.plugins.services.IdeBuildService
import com.itsaky.androidide.plugins.manager.services.IdeProjectServiceImpl
import com.itsaky.androidide.plugins.manager.services.IdeEditorServiceImpl
import com.itsaky.androidide.plugins.manager.services.IdeUIServiceImpl
import com.itsaky.androidide.plugins.manager.services.IdeBuildServiceImpl
import com.itsaky.androidide.plugins.manager.services.CogoProjectProvider
import com.itsaky.androidide.plugins.manager.services.AndroidIdeEditorProvider
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class PluginManager private constructor(
    private val context: Context,
    private val eventBus: Any, // EventBus reference to avoid direct dependency
    private val logger: PluginLogger
) {
    
    /**
     * Interface for providing the current Activity context for UI operations
     */
    interface ActivityProvider {
        fun getCurrentActivity(): Activity?
    }
    
    /**
     * Interface for validating file/path access for plugins
     */
    interface PluginPathValidator {
        fun isPathAllowed(path: File): Boolean
        fun getAllowedPaths(): List<String>
    }
    
    private var activityProvider: ActivityProvider? = null
    private var pathValidator: PluginPathValidator? = null
    
    // Configurable permissions for different services
    private var projectServicePermissions: Set<PluginPermission> = setOf(PluginPermission.FILESYSTEM_READ)
    private var editorServicePermissions: Set<PluginPermission> = setOf(PluginPermission.FILESYSTEM_READ)
    
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
    private val pluginStates = ConcurrentHashMap<String, Boolean>()
    private val pluginRegistry = PluginRegistry(context)
    private val securityManager = PluginSecurityManager()
    private val serviceRegistry = ServiceRegistryImpl()
    
    private val pluginsDir = File(context.filesDir, "plugins")
    
    // IDE service providers
    private val projectProvider = CogoProjectProvider()
    private val editorProvider = AndroidIdeEditorProvider()
    
    init {
        if (!pluginsDir.exists()) {
            pluginsDir.mkdirs()
        }
    }
    
    fun loadPlugins() {
        logger.info("Loading plugins from directory: ${pluginsDir.absolutePath}")
        
        // Load plugin states first
        loadPluginStates()
        
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
    
    /**
     * Load a plugin with enhanced error reporting and safety checks
     */
    fun loadPluginSafely(pluginFile: File): Result<IPlugin> {
        logger.info("Attempting to load plugin: ${pluginFile.name}")
        
        // Pre-flight checks
        if (!pluginFile.exists()) {
            val error = "Plugin file does not exist: ${pluginFile.absolutePath}"
            logger.error(error)
            return Result.failure(IllegalArgumentException(error))
        }
        
        if (!pluginFile.canRead()) {
            val error = "Cannot read plugin file: ${pluginFile.absolutePath}"
            logger.error(error)
            return Result.failure(IllegalArgumentException(error))
        }
        
        // Check if AndroidIDE integration is properly set up
        if (projectProvider == null) {
            logger.warn("ProjectProvider not set - plugins will have limited project access")
        }
        
        if (editorProvider == null) {
            logger.warn("EditorProvider not set - plugins will have limited editor access")
        }
        
        if (activityProvider == null) {
            logger.warn("ActivityProvider not set - plugins will have limited UI access")
        }
        
        return loadPlugin(pluginFile)
    }
    
    /**
     * Internal plugin loading method with detailed logging
     */
    fun loadPlugin(pluginFile: File): Result<IPlugin> {
        return try {
            logger.debug("Loading plugin from: ${pluginFile.absolutePath}")
            
            // Validate prerequisites
            if (!pluginFile.exists() || !pluginFile.canRead()) {
                return Result.failure(IllegalArgumentException("Plugin file does not exist or is not readable: ${pluginFile.absolutePath}"))
            }
            
            val manifest = PluginManifestParser.parseFromJar(pluginFile)
            if (manifest == null) {
                return Result.failure(IllegalArgumentException("Plugin manifest not found or invalid in ${pluginFile.name}"))
            }
            
            logger.debug("Parsed manifest for plugin: ${manifest.name} (${manifest.id})")
            
            if (!securityManager.validatePlugin(pluginFile, manifest)) {
                return Result.failure(SecurityException("Plugin failed security validation: ${manifest.id}"))
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
            
            logger.debug("Creating class loader for plugin: ${manifest.id}")
            val classLoader = try {
                PluginClassLoader(
                    pluginFile,
                    this::class.java.classLoader!!,
                    permissions
                )
            } catch (e: Exception) {
                logger.error("Failed to create class loader for plugin: ${manifest.id}", e)
                return Result.failure(e)
            }
            
            logger.debug("Loading main class: ${manifest.mainClass}")
            val pluginClass = try {
                classLoader.loadClass(manifest.mainClass)
            } catch (e: Exception) {
                logger.error("Failed to load main class ${manifest.mainClass} for plugin: ${manifest.id}", e)
                return Result.failure(e)
            }
            
            logger.debug("Creating plugin instance for: ${manifest.id}")
            val plugin = try {
                pluginClass.getDeclaredConstructor().newInstance() as IPlugin
            } catch (e: Exception) {
                logger.error("Failed to create plugin instance for: ${manifest.id}", e)
                return Result.failure(e)
            }
            
            logger.debug("Creating plugin context for: ${manifest.id}")
            val pluginContext = try {
                createPluginContext(manifest.id, classLoader, permissions)
            } catch (e: Exception) {
                logger.error("Failed to create plugin context for: ${manifest.id}", e)
                return Result.failure(e)
            }
            
            logger.debug("Initializing plugin: ${manifest.id}")
            val initResult = try {
                plugin.initialize(pluginContext)
            } catch (e: Exception) {
                logger.error("Plugin initialization threw exception for: ${manifest.id}", e)
                return Result.failure(e)
            }
            
            if (initResult) {
                // Get the saved enabled state (defaults to true for new plugins)
                val isEnabled = getPluginState(manifest.id)
                val loadedPlugin = LoadedPlugin(plugin, manifest, classLoader, pluginContext, isEnabled)
                loadedPlugins[manifest.id] = loadedPlugin
                
                // Only activate the plugin if it's enabled
                if (isEnabled) {
                    try {
                        plugin.activate()
                        logger.info("Successfully loaded and activated plugin: ${manifest.name} (${manifest.id})")
                    } catch (e: Exception) {
                        logger.error("Failed to activate plugin: ${manifest.id}", e)
                        // Keep plugin loaded but mark as disabled
                        loadedPlugin.isEnabled = false
                        savePluginState(manifest.id, false)
                    }
                } else {
                    logger.info("Successfully loaded plugin (disabled): ${manifest.name} (${manifest.id})")
                }
                
                Result.success(plugin)
            } else {
                logger.warn("Plugin initialization returned false for: ${manifest.id}")
                Result.failure(RuntimeException("Plugin initialization failed for: ${manifest.id}"))
            }
        } catch (e: Exception) {
            logger.error("Failed to load plugin from ${pluginFile.name}: ${e.javaClass.simpleName}: ${e.message}", e)
            
            // Provide more specific error information
            val errorDetail = when (e) {
                is ClassNotFoundException -> "Plugin main class not found: ${e.message}"
                is NoClassDefFoundError -> "Missing dependency class: ${e.message}"
                is SecurityException -> "Security validation failed: ${e.message}"
                is IllegalArgumentException -> "Invalid plugin configuration: ${e.message}"
                else -> "Unexpected error during plugin loading: ${e.message}"
            }
            
            logger.error("Detailed error: $errorDetail")
            Result.failure(RuntimeException(errorDetail, e))
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
                isEnabled = loadedPlugin.isEnabled,
                isLoaded = true
            )
        }
    }
    
    /**
     * Get all enabled plugin instances for UI integration
     */
    fun getAllPluginInstances(): List<IPlugin> {
        return loadedPlugins.values
            .filter { it.isEnabled }
            .map { it.plugin }
    }
    
    /**
     * Get all enabled plugins that implement UI extensions
     */
    fun getEnabledUIExtensions(): List<com.itsaky.androidide.plugins.extensions.UIExtension> {
        return loadedPlugins.values
            .filter { it.isEnabled }
            .map { it.plugin }
            .filterIsInstance<com.itsaky.androidide.plugins.extensions.UIExtension>()
    }
    
    fun enablePlugin(pluginId: String): Boolean {
        val loadedPlugin = loadedPlugins[pluginId] ?: return false
        
        if (loadedPlugin.isEnabled) {
            logger.info("Plugin $pluginId is already enabled")
            return true
        }
        
        return try {
            loadedPlugin.plugin.activate()
            loadedPlugin.isEnabled = true
            savePluginState(pluginId, true)
            logger.info("Enabled plugin: $pluginId")
            true
        } catch (e: Exception) {
            logger.error("Failed to enable plugin: $pluginId", e)
            false
        }
    }
    
    fun disablePlugin(pluginId: String): Boolean {
        val loadedPlugin = loadedPlugins[pluginId] ?: return false
        
        if (!loadedPlugin.isEnabled) {
            logger.info("Plugin $pluginId is already disabled")
            return true
        }
        
        return try {
            loadedPlugin.plugin.deactivate()
            loadedPlugin.isEnabled = false
            savePluginState(pluginId, false)
            logger.info("Disabled plugin: $pluginId")
            true
        } catch (e: Exception) {
            logger.error("Failed to disable plugin: $pluginId", e)
            false
        }
    }
    
    fun getServiceRegistry(): ServiceRegistry = serviceRegistry
    
    /**
     * Set the activity provider to enable UI operations in plugins
     */
    fun setActivityProvider(provider: ActivityProvider?) {
        this.activityProvider = provider
    }
    
    /**
     * Set the path validator for validating plugin file access
     */
    fun setPathValidator(validator: PluginPathValidator?) {
        this.pathValidator = validator
    }
    
    /**
     * Save plugin enabled state to persistent storage
     */
    private fun savePluginState(pluginId: String, enabled: Boolean) {
        try {
            pluginStates[pluginId] = enabled
            val prefsFile = File(context.filesDir, "plugin_states.properties")
            val properties = java.util.Properties()
            
            // Load existing states
            if (prefsFile.exists()) {
                prefsFile.inputStream().use { input ->
                    properties.load(input)
                }
            }
            
            // Update the state
            properties.setProperty(pluginId, enabled.toString())
            
            // Save back to file
            prefsFile.outputStream().use { output ->
                properties.store(output, "Plugin enabled/disabled states")
            }
            
            logger.debug("Saved plugin state: $pluginId = $enabled")
        } catch (e: Exception) {
            logger.error("Failed to save plugin state for $pluginId", e)
        }
    }
    
    /**
     * Load plugin enabled states from persistent storage
     */
    private fun loadPluginStates() {
        try {
            val prefsFile = File(context.filesDir, "plugin_states.properties")
            if (prefsFile.exists()) {
                val properties = java.util.Properties()
                prefsFile.inputStream().use { input ->
                    properties.load(input)
                }
                
                properties.forEach { key, value ->
                    val pluginId = key as String
                    val enabled = (value as String).toBoolean()
                    pluginStates[pluginId] = enabled
                    logger.debug("Loaded plugin state: $pluginId = $enabled")
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to load plugin states", e)
        }
    }
    
    /**
     * Get the saved enabled state for a plugin (defaults to true for new plugins)
     */
    private fun getPluginState(pluginId: String): Boolean {
        return pluginStates[pluginId] ?: true
    }
    
    /**
     * Configure required permissions for project service access
     */
    fun setProjectServicePermissions(permissions: Set<PluginPermission>) {
        this.projectServicePermissions = permissions
    }
    
    /**
     * Configure required permissions for editor service access
     */
    fun setEditorServicePermissions(permissions: Set<PluginPermission>) {
        this.editorServicePermissions = permissions
    }
    
    private fun createPluginContext(
        pluginId: String, 
        classLoader: ClassLoader, 
        permissions: Set<PluginPermission>
    ): PluginContext {
        // Create a plugin-specific service registry with permission-validated services
        val pluginServiceRegistry = ServiceRegistryImpl()
        
        logger.debug("Creating IDE services for plugin: $pluginId")
        
        // Only create services if providers are available, otherwise plugins will get null services
        // This prevents crashes but plugins should handle null service gracefully

        try {
            val projectService = IdeProjectServiceImpl(
                pluginId = pluginId,
                permissions = permissions,
                projectProvider = projectProvider,
                requiredPermissions = projectServicePermissions,
                pathValidator = pathValidator?.let { validator ->
                    object : IdeProjectServiceImpl.PathValidator {
                        override fun isPathAllowed(path: File): Boolean = validator.isPathAllowed(path)
                        override fun getAllowedPaths(): List<String> = validator.getAllowedPaths()
                    }
                }
            )
            pluginServiceRegistry.register(IdeProjectService::class.java, projectService)
            logger.debug("Registered project service for plugin: $pluginId")
        } catch (e: Exception) {
            logger.warn("Failed to create project service for plugin $pluginId: ${e.message}")
        }

        try {
            val editorService = IdeEditorServiceImpl(
                pluginId = pluginId,
                permissions = permissions,
                editorProvider = editorProvider,
                requiredPermissions = editorServicePermissions,
                pathValidator = pathValidator?.let { validator ->
                    object : IdeEditorServiceImpl.PathValidator {
                        override fun isPathAllowed(file: File): Boolean = validator.isPathAllowed(file)
                        override fun getAllowedPaths(): List<String> = validator.getAllowedPaths()
                    }
                }
            )
            pluginServiceRegistry.register(IdeEditorService::class.java, editorService)
            logger.debug("Registered editor service for plugin: $pluginId")
        } catch (e: Exception) {
            logger.warn("Failed to create editor service for plugin $pluginId: ${e.message}")
        }
        
        // UI service is always created, even if activityProvider is null
        try {
            val uiService = IdeUIServiceImpl(activityProvider)
            pluginServiceRegistry.register(IdeUIService::class.java, uiService)
            logger.debug("Registered UI service for plugin: $pluginId")
        } catch (e: Exception) {
            logger.warn("Failed to create UI service for plugin $pluginId: ${e.message}")
        }
        
        // Build service is always created to provide build status information
        try {
            val buildService = IdeBuildServiceImpl()
            pluginServiceRegistry.register(IdeBuildService::class.java, buildService)
            logger.debug("Registered build service for plugin: $pluginId")
        } catch (e: Exception) {
            logger.warn("Failed to create build service for plugin $pluginId: ${e.message}")
        }
        

        // Copy other services from global registry
        // TODO: Add mechanism to copy global services to plugin-specific registry
        
        return PluginContextImpl(
            androidContext = context,
            services = pluginServiceRegistry,
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
    val context: PluginContext,
    var isEnabled: Boolean = true
)