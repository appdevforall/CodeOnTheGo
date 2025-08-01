
package com.itsaky.androidide.plugins

interface IPlugin {
    val metadata: PluginMetadata
    
    fun initialize(context: PluginContext): Boolean
    fun activate(): Boolean
    fun deactivate(): Boolean
    fun dispose()
}

data class PluginMetadata(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val author: String,
    val minIdeVersion: String,
    val permissions: List<String> = emptyList(),
    val dependencies: List<String> = emptyList()
)

enum class PluginPermission(val key: String, val description: String) {
    FILESYSTEM_READ("filesystem.read", "Read files from project directory"),
    FILESYSTEM_WRITE("filesystem.write", "Write files to project directory"),
    NETWORK_ACCESS("network.access", "Access network resources"),
    SYSTEM_COMMANDS("system.commands", "Execute system commands"),
    IDE_SETTINGS("ide.settings", "Modify IDE settings"),
    PROJECT_STRUCTURE("project.structure", "Modify project structure")
}

data class PluginInfo(
    val metadata: PluginMetadata,
    val isEnabled: Boolean,
    val isLoaded: Boolean,
    val loadError: String? = null
)

interface PluginContext {
    val services: ServiceRegistry
    val logger: PluginLogger
    val resources: ResourceManager
    val pluginId: String
}

interface ServiceRegistry {
    fun <T> register(serviceClass: Class<T>, implementation: T)
    fun <T> get(serviceClass: Class<T>): T?
    fun <T> getAll(serviceClass: Class<T>): List<T>
    fun unregister(serviceClass: Class<*>)
}

inline fun <reified T> ServiceRegistry.register(implementation: T) {
    register(T::class.java, implementation)
}

inline fun <reified T> ServiceRegistry.get(): T? {
    return get(T::class.java)
}

inline fun <reified T> ServiceRegistry.getAll(): List<T> {
    return getAll(T::class.java)
}

interface ResourceManager {
    fun getPluginDirectory(): java.io.File
    fun getPluginFile(path: String): java.io.File
    fun getPluginResource(name: String): ByteArray?
    fun hasPermission(permission: PluginPermission): Boolean
    fun requestPermission(permission: PluginPermission): Boolean
}

interface PluginLogger {
    val pluginId: String
    fun debug(message: String)
    fun debug(message: String, error: Throwable)
    fun info(message: String)
    fun info(message: String, error: Throwable)
    fun warn(message: String)
    fun warn(message: String, error: Throwable)
    fun error(message: String)
    fun error(message: String, error: Throwable)
}