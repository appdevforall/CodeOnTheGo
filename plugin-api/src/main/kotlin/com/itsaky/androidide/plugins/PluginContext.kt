

package com.itsaky.androidide.plugins

import android.content.Context
// Note: EventBus and ILogger are referenced but not directly imported to avoid Android dependencies
import java.io.File

interface PluginContext {
    val androidContext: Context
    val services: ServiceRegistry
    val eventBus: Any // EventBus reference to avoid direct dependency
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
    fun getPluginDirectory(): File
    fun getPluginFile(path: String): File
    fun getPluginResource(name: String): ByteArray?
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