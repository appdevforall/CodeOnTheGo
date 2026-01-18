

package com.itsaky.androidide.plugins.manager.core

import android.app.Activity
import android.content.Context
import com.itsaky.androidide.plugins.*
import com.itsaky.androidide.plugins.base.PluginFragmentHelper
import com.itsaky.androidide.plugins.services.IdeProjectService
import com.itsaky.androidide.plugins.services.IdeUIService
import com.itsaky.androidide.plugins.services.IdeBuildService
import com.itsaky.androidide.plugins.manager.services.IdeProjectServiceImpl
import com.itsaky.androidide.plugins.manager.services.IdeUIServiceImpl
import com.itsaky.androidide.plugins.manager.services.IdeBuildServiceImpl
import com.itsaky.androidide.plugins.manager.services.CogoProjectProvider
import com.itsaky.androidide.plugins.manager.services.IdeTooltipServiceImpl
import com.itsaky.androidide.plugins.manager.services.IdeEditorTabServiceImpl
import com.itsaky.androidide.plugins.extensions.DocumentationExtension
import com.itsaky.androidide.plugins.extensions.UIExtension
import com.itsaky.androidide.plugins.manager.loaders.PluginManifest
import com.itsaky.androidide.plugins.manager.loaders.PluginLoader
import com.itsaky.androidide.plugins.manager.security.PluginSecurityManager
import com.itsaky.androidide.plugins.manager.context.PluginContextImpl
import com.itsaky.androidide.plugins.manager.context.PluginLoggerImpl
import com.itsaky.androidide.plugins.manager.context.PluginRegistry
import com.itsaky.androidide.plugins.manager.context.ResourceManagerImpl
import com.itsaky.androidide.plugins.manager.context.ServiceRegistryImpl
import com.itsaky.androidide.plugins.manager.documentation.PluginDocumentationManager
import com.itsaky.androidide.plugins.services.IdeTooltipService
import com.itsaky.androidide.plugins.services.IdeEditorTabService
import com.itsaky.androidide.plugins.services.IdeFileService
import com.itsaky.androidide.plugins.services.IdeSidebarService
import com.itsaky.androidide.plugins.manager.services.IdeFileServiceImpl
import com.itsaky.androidide.plugins.manager.services.IdeSidebarServiceImpl
import com.itsaky.androidide.actions.SidebarSlotManager
import com.itsaky.androidide.actions.SidebarSlotExceededException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    fun interface ActivityProvider {
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
    private val documentationManager = PluginDocumentationManager(context)

    // Helper methods for cleaner error handling
    private fun <T> executeWithErrorHandling(
        operationDescription: String,
        pluginId: String? = null,
        operation: () -> T
    ): Result<T> {
        return runCatching(operation).onFailure { exception ->
            val contextInfo = if (pluginId != null) " for plugin $pluginId" else ""
            logger.error("Failed to $operationDescription$contextInfo", exception)
        }
    }

    private fun <T> registerServiceWithErrorHandling(
        serviceRegistry: ServiceRegistryImpl,
        serviceClass: Class<T>,
        pluginId: String,
        serviceName: String,
        serviceFactory: () -> T
    ) {
        executeWithErrorHandling("create $serviceName service", pluginId) {
            val serviceInstance = serviceFactory()
            serviceRegistry.register(serviceClass, serviceInstance)
            logger.debug("Registered $serviceName service for plugin: $pluginId")
        }
    }

    // IDE service providers
    private val projectProvider = CogoProjectProvider()
    
    init {
        if (!pluginsDir.exists()) {
            pluginsDir.mkdirs()
        }
    }
    
    suspend fun loadPlugins() = withContext(Dispatchers.IO) {
        logger.info("Loading plugins from directory: ${pluginsDir.absolutePath}")

        // Load plugin states first
        loadPluginStates()

        // After loading all plugins, verify documentation for already loaded plugins
        verifyDocumentationForLoadedPlugins()

        val pluginFiles = pluginsDir.listFiles { file ->
            file.isFile && file.name.endsWith(".cgp", ignoreCase = true)
        } ?: return@withContext

        logger.info("Found ${pluginFiles.size} plugin files")

        // Load plugins in parallel
        val loadJobs = pluginFiles.map { pluginFile ->
            async {
                try {
                    logger.debug("Loading plugin: ${pluginFile.name}")
                    loadPlugin(pluginFile)
                } catch (e: Exception) {
                    logger.error("Failed to load plugin from ${pluginFile.name}", e)
                }
            }
        }

        // Wait for all plugins to load
        loadJobs.awaitAll()

        logger.info("Successfully loaded ${loadedPlugins.size} plugins")

        // Verify documentation after all plugins are loaded
        verifyDocumentationForLoadedPlugins()
    }

    /**
     * Verify and recreate documentation for all loaded plugins that support it.
     * This ensures documentation is present even after database updates.
     */
    private suspend fun verifyDocumentationForLoadedPlugins() = withContext(Dispatchers.IO) {
        val pluginsWithDocs = loadedPlugins.values
            .filter { it.plugin is DocumentationExtension }
            .associate { it.manifest.id to it.plugin as DocumentationExtension }

        if (pluginsWithDocs.isNotEmpty()) {
            logger.info("Verifying documentation for ${pluginsWithDocs.size} plugins")
            val recreatedCount = documentationManager.verifyAllPluginDocumentation(pluginsWithDocs)
            if (recreatedCount > 0) {
                logger.info("Recreated missing documentation for $recreatedCount plugins")
            }
        }
    }

    /**
     * Public method to manually trigger documentation verification.
     * Can be called when database changes are detected.
     */
    suspend fun verifyAllPluginDocumentation() = withContext(Dispatchers.IO) {
        verifyDocumentationForLoadedPlugins()
    }

    fun getPluginMetadataOnly(pluginFile: File): Result<PluginManifest> {
        if (!pluginFile.exists()) {
            return Result.failure(IllegalArgumentException("Plugin file does not exist: ${pluginFile.absolutePath}"))
        }
        if (!pluginFile.canRead()) {
            return Result.failure(IllegalArgumentException("Cannot read plugin file: ${pluginFile.absolutePath}"))
        }
        val pluginLoader = PluginLoader(context, pluginFile)
        val manifest = pluginLoader.getPluginMetadata()
            ?: return Result.failure(IllegalArgumentException("Plugin manifest not found in: ${pluginFile.name}"))
        return Result.success(manifest)
    }

    /**
     * Load plugin and return both the plugin instance and its metadata
     */
    fun loadPluginWithMetadata(pluginFile: File): Result<Pair<IPlugin, PluginManifest>> {
        logger.info("Attempting to load plugin with metadata: ${pluginFile.name}")

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

        if (!pluginFile.name.endsWith(".cgp", ignoreCase = true)) {
            val error = "Only CGP plugins are supported. File: ${pluginFile.name}"
            logger.error(error)
            return Result.failure(IllegalArgumentException(error))
        }

        // Create plugin loader
        val pluginLoader = PluginLoader(context, pluginFile)

        // Get plugin manifest from 
        val manifest = pluginLoader.getPluginMetadata()
        if (manifest == null) {
            return Result.failure(IllegalArgumentException("Plugin manifest not found in CGP: ${pluginFile.name}"))
        }

        // Load the plugin
        val pluginResult = loadPlugin(pluginFile)
        return if (pluginResult.isSuccess) {
            val plugin = pluginResult.getOrNull()!!
            Result.success(plugin to manifest)
        } else {
            Result.failure(pluginResult.exceptionOrNull() ?: RuntimeException("Failed to load plugin"))
        }
    }
    
    /**
     * Load plugin with full resource support
     */
    fun loadPlugin(file: File): Result<IPlugin> {
        var reservedSlotsPluginId: String? = null
        return try {
            logger.debug("Loading plugin from: ${file.absolutePath}")

            // Validate prerequisites
            if (!file.exists() || !file.canRead()) {
                return Result.failure(IllegalArgumentException("Plugin  does not exist or is not readable: ${file.absolutePath}"))
            }

            // Create plugin loader
            val pluginLoader = PluginLoader(context, file)

            // Validate signature
            if (!pluginLoader.validateSignature()) {
                logger.warn("signature validation failed for: ${file.name}")
                // Continue anyway for development
            }

            // Get plugin manifest from 
            val manifest = pluginLoader.getPluginMetadata()
            if (manifest == null) {
                return Result.failure(IllegalArgumentException("Plugin manifest not found in: ${file.name}"))
            }

            logger.debug("Parsed manifest for plugin: ${manifest.name} (${manifest.id})")

            if (!securityManager.validatePlugin(file, manifest)) {
                return Result.failure(SecurityException("plugin failed security validation: ${manifest.id}"))
            }

            // Validate sidebar slots BEFORE loading plugin code
            if (manifest.sidebarItems > 0) {
                val available = SidebarSlotManager.getAvailableSlotsForPlugins()
                if (manifest.sidebarItems > available) {
                    return Result.failure(
                        SidebarSlotExceededException(manifest.sidebarItems, available, manifest.id)
                    )
                }
                SidebarSlotManager.reservePluginSlots(manifest.id, manifest.sidebarItems)
                reservedSlotsPluginId = manifest.id
            }

            // Parse permissions
            val permissions = executeWithErrorHandling("parse permissions", manifest.id) {
                manifest.permissions.mapNotNull { permissionStr ->
                    PluginPermission.values().find { it.key == permissionStr } ?: run {
                        logger.warn("Invalid permission in plugin manifest: $permissionStr")
                        null
                    }
                }.toSet()
            }.getOrElse { emptySet() }

            // Load plugin classes
            val classLoader = pluginLoader.loadPluginClasses(this::class.java.classLoader!!)
            if (classLoader == null) {
                if (manifest.sidebarItems > 0) {
                    SidebarSlotManager.releasePluginSlots(manifest.id)
                }
                return Result.failure(RuntimeException("Failed to create class loader for  plugin: ${manifest.id}"))
            }

            logger.debug("Loading main class: ${manifest.mainClass}")
            val pluginClass = executeWithErrorHandling("load main class ${manifest.mainClass}", manifest.id) {
                classLoader.loadClass(manifest.mainClass)
            }.getOrElse {
                if (manifest.sidebarItems > 0) {
                    SidebarSlotManager.releasePluginSlots(manifest.id)
                }
                return Result.failure(it)
            }

            logger.debug("Creating plugin instance for: ${manifest.id}")
            val plugin = executeWithErrorHandling("create plugin instance", manifest.id) {
                pluginClass.getDeclaredConstructor().newInstance() as IPlugin
            }.getOrElse {
                if (manifest.sidebarItems > 0) {
                    SidebarSlotManager.releasePluginSlots(manifest.id)
                }
                return Result.failure(it)
            }

            // Create plugin context with  resources
            val ctx = pluginLoader.createPluginContext()
            if (ctx == null) {
                if (manifest.sidebarItems > 0) {
                    SidebarSlotManager.releasePluginSlots(manifest.id)
                }
                return Result.failure(RuntimeException("Failed to create plugin context for: ${manifest.id}"))
            }

            val pluginContext = createPluginContextWithResources(manifest.id, classLoader, permissions, ctx)

            logger.debug("Initializing  plugin: ${manifest.id}")
            val initResult = try {
                plugin.initialize(pluginContext)
            } catch (e: Exception) {
                logger.error("Plugin initialization threw exception for: ${manifest.id}", e)
                if (manifest.sidebarItems > 0) {
                    SidebarSlotManager.releasePluginSlots(manifest.id)
                }
                return Result.failure(e)
            }

            if (initResult) {
                // Register the plugin's resource context for UI components
                PluginFragmentHelper.registerPluginContext(manifest.id, ctx)
                logger.debug("Registered resource context for plugin: ${manifest.id}")
                // Register the service registry for fragments to access services
                PluginFragmentHelper.registerServiceRegistry(manifest.id, pluginContext.services)
                logger.debug("Registered service registry for plugin: ${manifest.id}")

                val isEnabled = getPluginState(manifest.id)
                val loadedPlugin = LoadedPlugin(plugin, manifest, classLoader, pluginContext, isEnabled)
                loadedPlugins[manifest.id] = loadedPlugin
                if (isEnabled) {
                    try {
                        plugin.activate()
                        logger.info("Successfully loaded and activated  plugin: ${manifest.name} (${manifest.id})")

                        // Verify and install/recreate documentation if plugin implements DocumentationExtension
                        if (plugin is DocumentationExtension) {
                            CoroutineScope(Dispatchers.IO).launch {
                                try {
                                    val docResult = documentationManager.verifyAndRecreateDocumentation(manifest.id, plugin)
                                    if (docResult) {
                                        logger.info("Documentation verified/installed for plugin: ${manifest.id}")
                                    } else {
                                        logger.warn("Failed to verify/install documentation for plugin: ${manifest.id}")
                                    }
                                } catch (e: Exception) {
                                    logger.error("Error verifying/installing documentation for plugin: ${manifest.id}", e)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        logger.error("Failed to activate  plugin: ${manifest.id}", e)
                        loadedPlugin.isEnabled = false
                        savePluginState(manifest.id, false)
                    }
                } else {
                    logger.info("Successfully loaded  plugin (disabled): ${manifest.name} (${manifest.id})")
                }

                Result.success(plugin)
            } else {
                logger.warn(" plugin initialization returned false for: ${manifest.id}")
                if (manifest.sidebarItems > 0) {
                    SidebarSlotManager.releasePluginSlots(manifest.id)
                }
                Result.failure(RuntimeException(" plugin initialization failed for: ${manifest.id}"))
            }
        } catch (e: Exception) {
            reservedSlotsPluginId?.let { pluginId ->
                SidebarSlotManager.releasePluginSlots(pluginId)
            }
            logger.error("Failed to load  plugin from ${file.name}: ${e.javaClass.simpleName}: ${e.message}", e)
            Result.failure(RuntimeException("Error loading  plugin: ${e.message}", e))
        }
    }

    fun unloadPlugin(pluginId: String): Boolean {
        val loadedPlugin = loadedPlugins.remove(pluginId) ?: return false

        try {
            // Remove documentation if plugin implements DocumentationExtension
            if (loadedPlugin.plugin is DocumentationExtension) {
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val docResult = documentationManager.removePluginDocumentation(pluginId, loadedPlugin.plugin)
                        if (docResult) {
                            logger.info("Removed documentation for plugin: $pluginId")
                        } else {
                            logger.warn("Failed to remove documentation for plugin: $pluginId")
                        }
                    } catch (e: Exception) {
                        logger.error("Error removing documentation for plugin: $pluginId", e)
                    }
                }
            }

            loadedPlugin.plugin.deactivate()
            loadedPlugin.plugin.dispose()

            // Unregister the plugin's resource context
            PluginFragmentHelper.unregisterPluginContext(pluginId)

            logger.info("Unloaded plugin: $pluginId")
            return true
        } catch (e: Exception) {
            logger.error("Failed to unload plugin: $pluginId", e)
            return false
        }
    }


    fun uninstallPlugin(pluginId: String): Boolean {
        logger.info("=== Starting uninstall for plugin: $pluginId ===")

        // Release sidebar slots reserved by this plugin
        SidebarSlotManager.releasePluginSlots(pluginId)

        // Clean up sidebar actions BEFORE unloading the plugin
        cleanupSidebarActions(pluginId)

        // Then unload the plugin from memory
        val unloaded = unloadPlugin(pluginId)
        if (!unloaded) {
            logger.warn("Could not unload plugin from memory: $pluginId (may already be unloaded)")
        } else {
            logger.info("Successfully unloaded plugin from memory: $pluginId")
        }

        // Find and delete the plugin file (CGP)
        val pluginFiles = pluginsDir.listFiles { file ->
            file.isFile && file.name.endsWith(".cgp", ignoreCase = true)
        }

        if (pluginFiles == null || pluginFiles.isEmpty()) {
            logger.error("No plugin files found in plugins directory")
            return false
        }

        logger.info("Found ${pluginFiles.size} plugin files to check")
        var deleted = false
        for (pluginFile in pluginFiles) {
            try {
                // Check if this  contains the plugin we want to delete
                val Loader = PluginLoader(context, pluginFile)
                val manifest = Loader.getPluginMetadata()

                if (manifest != null) {
                    if (manifest.id == pluginId) {
                        if (pluginFile.delete()) {
                            deleted = true
                            break // Found and deleted the right file
                        } else {
                            logger.error("File exists: ${pluginFile.exists()}, Can write: ${pluginFile.canWrite()}")
                        }
                    }
                } else {
                    logger.warn("Could not read manifest from ${pluginFile.name}")
                }
            } catch (e: Exception) {
                logger.error("Error checking plugin file ${pluginFile.name}: ${e.message}", e)
            }
        }

        // Remove plugin state and cleanup contributions
        if (deleted) {
            removePluginState(pluginId)
            cleanupPluginCacheFiles(pluginId)
            logger.info("Plugin uninstall completed successfully: $pluginId")
        } else {
            logger.error("Failed to uninstall plugin: $pluginId - file not found or could not be deleted")
        }
        logger.info("Uninstall process ended for plugin: $pluginId (success: $deleted) ===")
        return deleted
    }
    
    fun getPlugin(pluginId: String): IPlugin? {
        return loadedPlugins[pluginId]?.plugin
    }
    
    fun getAllPlugins(): List<PluginInfo> {
        return loadedPlugins.values.map { loadedPlugin ->
            PluginInfo(
                // Use manifest from AndroidManifest, not the plugin's hardcoded metadata
                metadata = PluginMetadata(
                    id = loadedPlugin.manifest.id,
                    name = loadedPlugin.manifest.name,
                    version = loadedPlugin.manifest.version,
                    description = loadedPlugin.manifest.description,
                    author = loadedPlugin.manifest.author,
                    minIdeVersion = loadedPlugin.manifest.minIdeVersion,
                    dependencies = loadedPlugin.manifest.dependencies,
                    permissions = loadedPlugin.manifest.permissions
                ),
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

    fun getPluginIdForInstance(plugin: IPlugin): String? {
        return loadedPlugins.entries
            .find { it.value.plugin === plugin }
            ?.key
    }

    fun getClassLoaderForPlugin(plugin: IPlugin): ClassLoader? {
        return loadedPlugins.values
            .find { it.plugin === plugin }
            ?.classLoader
    }

    fun getClassLoaderForPluginId(pluginId: String): ClassLoader? {
        return loadedPlugins[pluginId]?.classLoader
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
        executeWithErrorHandling("save plugin state", pluginId) {
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
        }
    }
    
    /**
     * Load plugin enabled states from persistent storage
     */
    private fun loadPluginStates() {
        executeWithErrorHandling("load plugin states") {
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
        }
    }
    
    /**
     * Get the saved enabled state for a plugin (defaults to true for new plugins)
     */
    private fun getPluginState(pluginId: String): Boolean {
        return pluginStates[pluginId] ?: true
    }

    private fun removePluginState(pluginId: String) {
        executeWithErrorHandling("remove plugin state", pluginId) {
            pluginStates.remove(pluginId)
            val prefsFile = File(context.filesDir, "plugin_states.properties")
            if (prefsFile.exists()) {
                val properties = java.util.Properties()
                properties.load(prefsFile.inputStream())
                properties.remove(pluginId)
                properties.store(prefsFile.outputStream(), "Plugin states")
                logger.debug("Removed plugin state: $pluginId")
            }
        }
    }
    
    /**
     * Configure required permissions for project service access
     */
    fun setProjectServicePermissions(permissions: Set<PluginPermission>) {
        this.projectServicePermissions = permissions
    }
    
    
    /**
     * Create plugin context with  resources
     */
    private fun createPluginContextWithResources(
        pluginId: String,
        classLoader: ClassLoader,
        permissions: Set<PluginPermission>,
        resourceContext: Context
    ): PluginContext {
        // Create a plugin-specific service registry with permission-validated services
        val pluginServiceRegistry = ServiceRegistryImpl()

        logger.debug("Creating IDE services with resources for plugin: $pluginId")

        // Create services with resource context
        registerServiceWithErrorHandling(
            pluginServiceRegistry,
            IdeProjectService::class.java,
            pluginId,
            "project"
        ) {
            IdeProjectServiceImpl(
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
        }


        registerServiceWithErrorHandling(
            pluginServiceRegistry,
            IdeUIService::class.java,
            pluginId,
            "UI"
        ) {
            IdeUIServiceImpl(activityProvider)
        }

        registerServiceWithErrorHandling(
            pluginServiceRegistry,
            IdeBuildService::class.java,
            pluginId,
            "build"
        ) {
            IdeBuildServiceImpl.getInstance()
        }

        // Tooltip service for showing help documentation
        // Use main app context for tooltip service to access app's tooltip layouts
        registerServiceWithErrorHandling(
            pluginServiceRegistry,
            IdeTooltipService::class.java,
            pluginId,
            "tooltip"
        ) {
            IdeTooltipServiceImpl(context)
        }

        // Editor tab service for plugin editor tab integration
        registerServiceWithErrorHandling(
            pluginServiceRegistry,
            IdeEditorTabService::class.java,
            pluginId,
            "editor_tab"
        ) {
            IdeEditorTabServiceImpl(activityProvider)
        }

        // File service for editing project files
        registerServiceWithErrorHandling(
            pluginServiceRegistry,
            IdeFileService::class.java,
            pluginId,
            "file"
        ) {
            IdeFileServiceImpl(
                pluginId = pluginId,
                permissions = permissions,
                pathValidator = pathValidator?.let { validator ->
                    object : IdeFileServiceImpl.PathValidator {
                        override fun isPathAllowed(path: File): Boolean = validator.isPathAllowed(path)
                        override fun getAllowedPaths(): List<String> = validator.getAllowedPaths()
                    }
                }
            )
        }

        // Sidebar service for plugin sidebar slot management
        registerServiceWithErrorHandling(
            pluginServiceRegistry,
            IdeSidebarService::class.java,
            pluginId,
            "sidebar"
        ) {
            IdeSidebarServiceImpl(pluginId)
        }

        // Create PluginContext with resource context
        return PluginContextImpl(
            androidContext = resourceContext, // Use the resource context instead of app context
            services = pluginServiceRegistry,
            eventBus = eventBus,
            logger = PluginLoggerImpl(pluginId, logger),
            resources = ResourceManagerImpl(pluginId, pluginsDir, classLoader),
            pluginId = pluginId
        )
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

        registerServiceWithErrorHandling(
            pluginServiceRegistry,
            IdeProjectService::class.java,
            pluginId,
            "project"
        ) {
            IdeProjectServiceImpl(
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
        }


        // UI service is always created, even if activityProvider is null
        registerServiceWithErrorHandling(
            pluginServiceRegistry,
            IdeUIService::class.java,
            pluginId,
            "UI"
        ) {
            IdeUIServiceImpl(activityProvider)
        }

        // Build service is always created to provide build status information
        registerServiceWithErrorHandling(
            pluginServiceRegistry,
            IdeBuildService::class.java,
            pluginId,
            "build"
        ) {
            IdeBuildServiceImpl.getInstance()
        }

        // Tooltip service for showing documentation tooltips
        registerServiceWithErrorHandling(
            pluginServiceRegistry,
            IdeTooltipService::class.java,
            pluginId,
            "tooltip"
        ) {
            IdeTooltipServiceImpl(context)
        }

        // Editor tab service for plugin editor tab integration
        registerServiceWithErrorHandling(
            pluginServiceRegistry,
            IdeEditorTabService::class.java,
            pluginId,
            "editor_tab"
        ) {
            IdeEditorTabServiceImpl(activityProvider)
        }

        // File service for editing project files
        registerServiceWithErrorHandling(
            pluginServiceRegistry,
            IdeFileService::class.java,
            pluginId,
            "file"
        ) {
            IdeFileServiceImpl(
                pluginId = pluginId,
                permissions = permissions,
                pathValidator = pathValidator?.let { validator ->
                    object : IdeFileServiceImpl.PathValidator {
                        override fun isPathAllowed(path: File): Boolean = validator.isPathAllowed(path)
                        override fun getAllowedPaths(): List<String> = validator.getAllowedPaths()
                    }
                }
            )
        }

        // Sidebar service for plugin sidebar slot management
        registerServiceWithErrorHandling(
            pluginServiceRegistry,
            IdeSidebarService::class.java,
            pluginId,
            "sidebar"
        ) {
            IdeSidebarServiceImpl(pluginId)
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

    /**
     * Clean up ALL plugin files and cache directories
     */
    private fun cleanupPluginCacheFiles(pluginId: String) {
        executeWithErrorHandling("cleanup plugin cache files", pluginId) {
            logger.debug("Cleaning up ALL files and cache for plugin: $pluginId")

            // Clean up plugin's own directory if it exists
            val pluginDir = File(pluginsDir, pluginId)
            if (pluginDir.exists()) {
                val deleted = pluginDir.deleteRecursively()
                logger.debug("Deleted plugin directory: ${pluginDir.absolutePath} (success: $deleted)")
            }

            // Clean up ART cache files in oat directory
            try {
                val oatDir = File(pluginsDir, "oat")
                if (oatDir.exists() && oatDir.isDirectory) {
                    oatDir.walkTopDown().forEach { file ->
                        if (file.name.contains(pluginId)) {
                            val deleted = file.deleteRecursively()
                            logger.debug("Deleted ART cache: ${file.absolutePath} (success: $deleted)")
                        }
                    }
                }
            } catch (e: Exception) {
                logger.warn("Failed to cleanup ART cache files for: $pluginId", e)
            }

            // Clean up any other directories or files that contain the plugin ID
            try {
                pluginsDir.walkTopDown().forEach { file ->
                    if (file != pluginsDir && file.name.contains(pluginId)) {
                        val deleted = file.deleteRecursively()
                        logger.debug("Deleted plugin-related item: ${file.absolutePath} (success: $deleted)")
                    }
                }
            } catch (e: Exception) {
                logger.warn("Failed to cleanup plugin-related files for: $pluginId", e)
            }

            logger.debug("Complete plugin cleanup finished for: $pluginId")
        }
    }

    /**
     * Clean up sidebar actions from the current session's action registry
     */
    private fun cleanupSidebarActions(pluginId: String) {
        executeWithErrorHandling("cleanup sidebar actions", pluginId) {
            logger.debug("Cleaning up sidebar actions for plugin: $pluginId")

            val plugin = loadedPlugins[pluginId]?.plugin
            if (plugin is UIExtension) {
                val registryClass = Class.forName("com.itsaky.androidide.actions.ActionsRegistry")
                val getInstanceMethod = registryClass.getMethod("getInstance")
                val registry = getInstanceMethod.invoke(null)

                plugin.getSideMenuItems().forEach { navItem ->
                    val actionId = "plugin_sidebar_${navItem.id}"
                    val unregisterMethod = registryClass.getMethod("unregisterAction", String::class.java)
                    val success = unregisterMethod.invoke(registry, actionId) as Boolean
                    logger.debug("Unregistered sidebar action: $actionId (success: $success)")
                }

                logger.debug("Sidebar actions cleanup completed for: $pluginId")
            } else {
                logger.debug("Plugin $pluginId does not implement UIExtension, no sidebar actions to cleanup")
            }
        }
    }

}

data class LoadedPlugin(
    val plugin: IPlugin,
    val manifest: PluginManifest,
    val classLoader: ClassLoader,
    val context: PluginContext,
    var isEnabled: Boolean = true
)