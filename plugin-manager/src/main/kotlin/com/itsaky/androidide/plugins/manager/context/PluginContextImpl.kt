

package com.itsaky.androidide.plugins.manager.context

import android.content.Context
import android.content.SharedPreferences
import android.content.res.AssetManager
import android.util.Log
import com.itsaky.androidide.plugins.*
import com.itsaky.androidide.plugins.manager.security.PluginSecurityManager
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

class PluginContextImpl(
    override val androidContext: Context,
    override val services: ServiceRegistry,
    override val eventBus: Any,
    override val logger: PluginLogger,
    override val resources: ResourceManager,
    override val pluginId: String,
    private val sharedServices: SharedServiceRegistry? = null,
    private val pluginInfoProvider: ((String) -> PluginInfo?)? = null,
    private val lifecycleDispatcher: PluginLifecycleDispatcher? = null
) : PluginContext {

    // Cross-plugin services are scoped by the provider's pluginId: a plugin publishes under its
    // own id (this.pluginId) and can only read/remove its own, while getPluginService() honours
    // the requested provider id instead of matching any registration of the same type.
    override fun <T> getPluginService(pluginId: String, serviceClass: Class<T>): T? =
        sharedServices?.get(pluginId, serviceClass)

    override fun isPluginActive(pluginId: String): Boolean =
        pluginInfoProvider?.invoke(pluginId)?.let { it.isLoaded && it.isEnabled } ?: false

    override fun getPluginVersion(pluginId: String): String? =
        pluginInfoProvider?.invoke(pluginId)?.metadata?.version

    override fun <T> registerService(serviceClass: Class<T>, serviceImpl: T) {
        sharedServices?.register(pluginId, serviceClass, serviceImpl as Any)
    }

    override fun <T> unregisterService(serviceClass: Class<T>) {
        sharedServices?.unregister(pluginId, serviceClass)
    }

    override fun getProvidedServices(): List<String> =
        sharedServices?.providedBy(pluginId) ?: emptyList()

    override fun getPluginDataDir(): File {
        // Return plugin's data directory (already exists via ResourceManager)
        return resources.getPluginDirectory()
    }

    override fun getAppFilesDir(): File {
        return androidContext.filesDir
    }

    override fun getPluginFilesDir(): File {
        return File(androidContext.filesDir, "plugins/$pluginId").apply { mkdirs() }
    }

    override fun getAppSharedPreferences(prefsName: String): SharedPreferences? {
        return try {
            androidContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        } catch (e: Exception) {
            logger.error("Failed to access app SharedPreferences: $prefsName", e)
            null
        }
    }

    override fun getPluginSharedPreferences(prefsName: String): SharedPreferences {
        return androidContext.getSharedPreferences("plugin_${pluginId}_${prefsName}", Context.MODE_PRIVATE)
    }

    override fun addPluginLifecycleListener(listener: PluginLifecycleListener) {
        lifecycleDispatcher?.addListener(pluginId, listener)
    }

    override fun removePluginLifecycleListener(listener: PluginLifecycleListener) {
        lifecycleDispatcher?.removeListener(pluginId, listener)
    }
}

/**
 * Stores and notifies [PluginLifecycleListener]s registered by plugins via
 * [PluginContext.addPluginLifecycleListener]. Listeners are tracked by the id of the plugin that
 * registered them, so they can be dropped wholesale when that plugin is unloaded — its classloader
 * is discarded then, and retaining listener references would leak it. A listener that throws can
 * never break dispatch to the others.
 */
class PluginLifecycleDispatcher {
    private val listenersByOwner = ConcurrentHashMap<String, CopyOnWriteArraySet<PluginLifecycleListener>>()

    fun addListener(ownerPluginId: String, listener: PluginLifecycleListener) {
        listenersByOwner.computeIfAbsent(ownerPluginId) { CopyOnWriteArraySet() }.add(listener)
    }

    fun removeListener(ownerPluginId: String, listener: PluginLifecycleListener) {
        listenersByOwner[ownerPluginId]?.remove(listener)
    }

    /** Drops every listener registered by [ownerPluginId] (called when that plugin is unloaded). */
    fun removeAllFrom(ownerPluginId: String) {
        listenersByOwner.remove(ownerPluginId)
    }

    fun notifyActivated(pluginId: String) =
        dispatch("onPluginActivated", pluginId) { it.onPluginActivated(pluginId) }

    fun notifyDeactivated(pluginId: String) =
        dispatch("onPluginDeactivated", pluginId) { it.onPluginDeactivated(pluginId) }

    fun notifyUninstalled(pluginId: String) =
        dispatch("onPluginUninstalled", pluginId) { it.onPluginUninstalled(pluginId) }

    private fun dispatch(event: String, pluginId: String, action: (PluginLifecycleListener) -> Unit) {
        listenersByOwner.values.forEach { listeners ->
            listeners.forEach { listener ->
                try {
                    action(listener)
                } catch (t: Throwable) {
                    Log.e(TAG, "Lifecycle listener threw during $event($pluginId)", t)
                }
            }
        }
    }

    private companion object {
        private const val TAG = "PluginLifecycleDispatcher"
    }
}

class ServiceRegistryImpl : ServiceRegistry {
    // Use fully qualified class name as key instead of Class object
    // This solves classloader isolation issues where the same interface
    // loaded by different classloaders creates different Class objects
    private val services = ConcurrentHashMap<String, MutableList<Any>>()

    override fun <T> register(serviceClass: Class<T>, implementation: T) {
        val key = serviceClass.name
        services.computeIfAbsent(key) { mutableListOf() }.add(implementation as Any)
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T> get(serviceClass: Class<T>): T? {
        val key = serviceClass.name
        return services[key]?.firstOrNull() as? T
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T> getAll(serviceClass: Class<T>): List<T> {
        val key = serviceClass.name
        return services[key]?.map { it as T } ?: emptyList()
    }

    override fun unregister(serviceClass: Class<*>) {
        val key = serviceClass.name
        services.remove(key)
    }
}

/**
 * Registry for services published across plugins via [PluginContext.registerService].
 *
 * Unlike [ServiceRegistryImpl] (which keys purely by class), entries here are scoped by the
 * *provider* plugin id, so one plugin can neither read nor unregister another plugin's service of
 * the same type. Each (providerId, serviceClass) holds a single implementation; re-registering the
 * same type from the same provider replaces it.
 */
class SharedServiceRegistry {
    // providerPluginId -> (serviceClassName -> implementation)
    private val byProvider = ConcurrentHashMap<String, ConcurrentHashMap<String, Any>>()

    fun register(providerId: String, serviceClass: Class<*>, implementation: Any) {
        byProvider.computeIfAbsent(providerId) { ConcurrentHashMap() }[serviceClass.name] = implementation
    }

    fun unregister(providerId: String, serviceClass: Class<*>) {
        byProvider[providerId]?.remove(serviceClass.name)
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> get(providerId: String, serviceClass: Class<T>): T? =
        byProvider[providerId]?.get(serviceClass.name) as? T

    /** Class names of every service the given provider currently publishes. */
    fun providedBy(providerId: String): List<String> =
        byProvider[providerId]?.keys?.toList() ?: emptyList()

    /**
     * A legacy class-keyed [ServiceRegistry] view backed by this store but pinned to a single
     * [providerId]. Backs [PluginManager.getServiceRegistry] so existing callers keep a working,
     * provider-scoped registry instead of the old cross-plugin-global one.
     */
    fun asRegistry(providerId: String): ServiceRegistry = object : ServiceRegistry {
        override fun <T> register(serviceClass: Class<T>, implementation: T) =
            this@SharedServiceRegistry.register(providerId, serviceClass, implementation as Any)

        override fun <T> get(serviceClass: Class<T>): T? =
            this@SharedServiceRegistry.get(providerId, serviceClass)

        override fun <T> getAll(serviceClass: Class<T>): List<T> =
            this@SharedServiceRegistry.get(providerId, serviceClass)?.let { listOf(it) } ?: emptyList()

        override fun unregister(serviceClass: Class<*>) =
            this@SharedServiceRegistry.unregister(providerId, serviceClass)
    }

    companion object {
        /** Reserved provider id for services the IDE host registers directly (not a plugin). */
        const val HOST_PROVIDER_ID = "__host__"
    }
}

class ResourceManagerImpl(
    private val pluginId: String,
    private val pluginsDir: File,
    private val classLoader: ClassLoader,
    private val assetManager: AssetManager? = null
) : ResourceManager {

    private val pluginDirectory = File(pluginsDir, pluginId)
    private val securityManager = PluginSecurityManager()

    init {
        if (!pluginDirectory.exists()) {
            pluginDirectory.mkdirs()
        }
    }

    override fun getPluginDirectory(): File = pluginDirectory

    override fun getPluginFile(path: String): File {
        val file = File(pluginDirectory, path)

        // Security check: ensure file is within plugin directory
        if (!file.canonicalPath.startsWith(pluginDirectory.canonicalPath)) {
            throw SecurityException("Access denied: Path traversal detected")
        }

        return file
    }

    override fun getPluginResource(name: String): ByteArray? {
        return try {
            classLoader.getResourceAsStream(name)?.use {
                it.readBytes()
            }
        } catch (e: Exception) {
            null
        }
    }

    override fun openPluginResource(name: String): InputStream? {
        return try {
            classLoader.getResourceAsStream(name)
        } catch (e: Exception) {
            null
        }
    }

    override fun openPluginAsset(path: String): InputStream? {
        val assets = assetManager ?: return null
        return try {
            assets.open(path)
        } catch (e: Exception) {
            null
        }
    }
}

class PluginLoggerImpl(
    override val pluginId: String,
    private val baseLogger: PluginLogger
) : PluginLogger {
    
    private fun formatMessage(message: String): String {
        return "[$pluginId] $message"
    }
    
    override fun debug(message: String) {
        baseLogger.debug(formatMessage(message))
    }
    
    override fun debug(message: String, error: Throwable) {
        baseLogger.debug(formatMessage(message), error)
    }
    
    override fun info(message: String) {
        baseLogger.info(formatMessage(message))
    }
    
    override fun info(message: String, error: Throwable) {
        baseLogger.info(formatMessage(message), error)
    }
    
    override fun warn(message: String) {
        baseLogger.warn(formatMessage(message))
    }
    
    override fun warn(message: String, error: Throwable) {
        baseLogger.warn(formatMessage(message), error)
    }
    
    override fun error(message: String) {
        baseLogger.error(formatMessage(message))
    }
    
    override fun error(message: String, error: Throwable) {
        baseLogger.error(formatMessage(message), error)
    }
}

class PluginRegistry(private val context: Context) {
    private val pluginInfos = mutableMapOf<String, PluginInfo>()
    
    fun registerPlugin(pluginInfo: PluginInfo) {
        pluginInfos[pluginInfo.metadata.id] = pluginInfo
    }
    
    fun unregisterPlugin(pluginId: String) {
        pluginInfos.remove(pluginId)
    }
    
    fun getPlugin(pluginId: String): PluginInfo? {
        return pluginInfos[pluginId]
    }
    
    fun getAllPlugins(): List<PluginInfo> {
        return pluginInfos.values.toList()
    }
}