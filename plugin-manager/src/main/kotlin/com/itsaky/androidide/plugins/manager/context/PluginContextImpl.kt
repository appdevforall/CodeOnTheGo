

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

class PluginContextImpl(
    override val androidContext: Context,
    override val services: ServiceRegistry,
    override val eventBus: Any,
    override val logger: PluginLogger,
    override val resources: ResourceManager,
    override val pluginId: String,
    private val sharedServices: ServiceRegistry? = null,
    private val pluginInfoProvider: ((String) -> PluginInfo?)? = null
) : PluginContext {

    override fun <T> getPluginService(pluginId: String, serviceClass: Class<T>): T? =
        sharedServices?.get(serviceClass)

    override fun isPluginActive(pluginId: String): Boolean =
        pluginInfoProvider?.invoke(pluginId)?.let { it.isLoaded && it.isEnabled } ?: false

    override fun getPluginVersion(pluginId: String): String? =
        pluginInfoProvider?.invoke(pluginId)?.metadata?.version

    override fun <T> registerService(serviceClass: Class<T>, serviceImpl: T) {
        sharedServices?.register(serviceClass, serviceImpl)
    }

    override fun <T> unregisterService(serviceClass: Class<T>) {
        sharedServices?.unregister(serviceClass)
    }

    override fun getProvidedServices(): List<String> {
        // TODO: Implement service listing in Phase 2
        return emptyList()
    }

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
        // TODO: Implement lifecycle listener registration in Phase 2
    }

    override fun removePluginLifecycleListener(listener: PluginLifecycleListener) {
        // TODO: Implement lifecycle listener removal in Phase 2
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