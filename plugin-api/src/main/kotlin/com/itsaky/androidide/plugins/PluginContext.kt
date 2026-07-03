

package com.itsaky.androidide.plugins

import android.content.Context
import android.content.SharedPreferences
// Note: EventBus and ILogger are referenced but not directly imported to avoid Android dependencies
import java.io.File
import java.io.InputStream

interface PluginContext {
    val androidContext: Context
    val services: ServiceRegistry
    val eventBus: Any // EventBus reference to avoid direct dependency
    val logger: PluginLogger
    val resources: ResourceManager
    val pluginId: String

    /**
     * Get the plugin's private data directory
     * Files stored here are isolated from other plugins
     *
     * @return Plugin data directory
     */
    fun getPluginDataDir(): File

    /**
     * Get the main application's files directory for data migration
     * Used to access data from the built-in Agent before migration to plugin
     *
     * @return Application files directory
     */
    fun getAppFilesDir(): File

    /**
     * Get the plugin's own files directory for storing migrated data
     * Used during data migration to copy chat history and settings
     *
     * @return Plugin files directory
     */
    fun getPluginFilesDir(): File

    /**
     * Get SharedPreferences from the main application for reading migration data
     * Used to read settings from built-in Agent preferences
     *
     * @param prefsName Name of the preferences file (e.g., "AgentPrefs")
     * @return SharedPreferences instance or null if not found
     */
    fun getAppSharedPreferences(prefsName: String): android.content.SharedPreferences?

    /**
     * Get SharedPreferences for the plugin for writing migrated data
     * Used to write settings to plugin storage during migration
     *
     * @param prefsName Name of the preferences file (e.g., "AgentSettings")
     * @return SharedPreferences instance
     */
    fun getPluginSharedPreferences(prefsName: String): android.content.SharedPreferences

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

    /**
     * Opens a plugin-bundled classpath resource as a stream. Prefer this over
     * [getPluginResource] for payloads larger than a few megabytes since
     * [getPluginResource] materializes the entire blob on the heap.
     *
     * Reads from `src/main/resources/`. For Android-style bundled binaries
     * (files under `src/main/assets/`), use [openPluginAsset] instead.
     *
     * Callers own the returned stream and must close it.
     */
    fun openPluginResource(name: String): InputStream?

    /**
     * Opens a file bundled in the plugin's `src/main/assets/` directory.
     * Preferred for large binary payloads such as toolchains or models,
     * since assets are the Android-native location and are not subject to
     * classpath scanning.
     *
     * Callers own the returned stream and must close it.
     *
     * @param path Path relative to the plugin's assets root. Supports
     *   subdirectories (e.g. `"toolchains/ndk-cmake.tar.xz"`).
     */
    fun openPluginAsset(path: String): InputStream?
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
