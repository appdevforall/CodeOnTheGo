

package com.itsaky.androidide.plugins.manager.context

import android.content.Context
import com.itsaky.androidide.plugins.*
import com.itsaky.androidide.plugins.manager.security.PluginSecurityManager
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class PluginContextImpl(
    override val androidContext: Context,
    override val services: ServiceRegistry,
    override val eventBus: Any,
    override val logger: PluginLogger,
    override val resources: ResourceManager,
    override val pluginId: String
) : PluginContext

class ServiceRegistryImpl : ServiceRegistry {
    private val services = ConcurrentHashMap<Class<*>, MutableList<Any>>()
    
    override fun <T> register(serviceClass: Class<T>, implementation: T) {
        services.computeIfAbsent(serviceClass) { mutableListOf() }.add(implementation as Any)
    }
    
    @Suppress("UNCHECKED_CAST")
    override fun <T> get(serviceClass: Class<T>): T? {
        return services[serviceClass]?.firstOrNull() as? T
    }
    
    @Suppress("UNCHECKED_CAST")
    override fun <T> getAll(serviceClass: Class<T>): List<T> {
        return services[serviceClass]?.map { it as T } ?: emptyList()
    }
    
    override fun unregister(serviceClass: Class<*>) {
        services.remove(serviceClass)
    }
}

class ResourceManagerImpl(
    private val pluginId: String,
    private val pluginsDir: File,
    private val classLoader: ClassLoader
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