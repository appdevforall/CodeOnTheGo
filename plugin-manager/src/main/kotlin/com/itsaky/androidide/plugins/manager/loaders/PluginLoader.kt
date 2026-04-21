package com.itsaky.androidide.plugins.manager.loaders

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.content.res.Resources
import android.os.Build
import android.util.Log
import dalvik.system.DexClassLoader
import java.io.File
import java.util.zip.ZipFile

/**
 * Loader for APK-based plugins with full resource support
 */
class PluginLoader(
    private val context: Context,
    private val pluginApk: File
) {
    companion object {
        private const val TAG = "PluginLoader"
    }

    private var pluginResources: Resources? = null
    private var pluginPackageInfo: PackageInfo? = null
    private var pluginClassLoader: DexClassLoader? = null
    private var nativeLibDir: File? = null

    /**
     * Load plugin resources from APK
     */
    fun loadPluginResources(): Resources? {
        if (pluginResources != null) {
            return pluginResources
        }

        try {
            // Get package info from APK
            val packageManager = context.packageManager
            pluginPackageInfo = packageManager.getPackageArchiveInfo(
                pluginApk.absolutePath,
                PackageManager.GET_META_DATA
            )

            if (pluginPackageInfo == null) {
                Log.e(TAG, "Failed to get package info from APK: ${pluginApk.absolutePath}")
                return null
            }

            // Set source directory for ApplicationInfo
            pluginPackageInfo?.applicationInfo?.sourceDir = pluginApk.absolutePath
            pluginPackageInfo?.applicationInfo?.publicSourceDir = pluginApk.absolutePath

            // Try to get resources using PackageManager
            try {
                val appInfo = pluginPackageInfo!!.applicationInfo
                if (appInfo != null) {
                    pluginResources = packageManager.getResourcesForApplication(appInfo)
                }
                Log.i(TAG, "Successfully loaded resources using PackageManager")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load resources via PackageManager, trying AssetManager: ${e.message}")

                // Fallback: Create AssetManager manually
                @Suppress("DEPRECATION")
                val assetManager = AssetManager::class.java.getDeclaredConstructor().newInstance()
                val addAssetPath = AssetManager::class.java.getMethod("addAssetPath", String::class.java)
                addAssetPath.invoke(assetManager, pluginApk.absolutePath)

                // Create Resources with the AssetManager
                @Suppress("DEPRECATION")
                pluginResources = Resources(
                    assetManager,
                    context.resources.displayMetrics,
                    context.resources.configuration
                )
                Log.i(TAG, "Successfully loaded resources using AssetManager")
            }

            return pluginResources
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load plugin resources: ${e.message}", e)
            return null
        }
    }

    /**
     * Load plugin classes from APK
     */
    fun loadPluginClasses(parentClassLoader: ClassLoader, nativeLibPath: String? = null): DexClassLoader? {
        if (pluginClassLoader != null) {
            return pluginClassLoader
        }

        try {
            val optimizedDir = File(context.codeCacheDir, "plugin_dex")
            if (!optimizedDir.exists()) {
                optimizedDir.mkdirs()
            }

            pluginClassLoader = DexClassLoader(
                pluginApk.absolutePath,
                optimizedDir.absolutePath,
                nativeLibPath,
                parentClassLoader
            )

            Log.i(TAG, "Successfully created DexClassLoader for plugin APK (nativeLibPath=$nativeLibPath)")
            return pluginClassLoader
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create DexClassLoader: ${e.message}", e)
            return null
        }
    }

    /**
     * Create a Context with plugin resources
     */
    fun createPluginContext(pluginId: String): Context? {
        // Ensure we have loaded resources first
        val resources = loadPluginResources()
        if (resources == null) {
            Log.e(TAG, "Failed to load plugin resources")
            return null
        }

        val packageInfo = pluginPackageInfo
        if (packageInfo == null) {
            Log.e(TAG, "Package info is null")
            return null
        }

        return PluginResourceContext(
            context,
            pluginId,
            resources,
            packageInfo,
            pluginClassLoader
        )
    }

    fun extractNativeLibs(pluginId: String): File? {
        val pluginNativeDir = File(context.getDir("plugin_native_libs", Context.MODE_PRIVATE), pluginId)
        if (pluginNativeDir.exists()) {
            nativeLibDir = pluginNativeDir
            return pluginNativeDir
        }

        val libPrefix = "lib/${Build.SUPPORTED_ABIS[0]}/"
        val targetPath = pluginNativeDir.toPath().toAbsolutePath().normalize()
        pluginNativeDir.mkdirs()

        var found = false
        ZipFile(pluginApk).use { zip ->
            zip.entries().asSequence()
                .filter { !it.isDirectory && it.name.startsWith(libPrefix) && it.name.endsWith(".so") }
                .forEach { entry ->
                    val outPath = targetPath.resolve(entry.name.substringAfterLast("/")).normalize()
                    if (outPath.startsWith(targetPath)) {
                        zip.getInputStream(entry).use { input ->
                            outPath.toFile().outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        outPath.toFile().setExecutable(true, false)
                        found = true
                    }
                }
        }

        if (!found) {
            pluginNativeDir.deleteRecursively()
            return null
        }

        nativeLibDir = pluginNativeDir
        return pluginNativeDir
    }

    fun isDebuggable(): Boolean {
        val packageInfo = pluginPackageInfo
            ?: context.packageManager.getPackageArchiveInfo(
                pluginApk.absolutePath,
                PackageManager.GET_META_DATA
            ) ?: return false
        val appInfo = packageInfo.applicationInfo ?: return false
        return (appInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    fun hasEntry(entryPath: String): Boolean =
        try {
            ZipFile(pluginApk).use { it.getEntry(entryPath) != null }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to inspect plugin APK for entry '$entryPath'", e)
            false
        }

    fun extractPluginIcons(pluginId: String, manifest: PluginManifest): Pair<String?, String?> {
        if (manifest.iconDay == null && manifest.iconNight == null) return null to null
        val iconDir = File(context.getDir("plugin_icons", Context.MODE_PRIVATE), pluginId)
        iconDir.deleteRecursively()
        iconDir.mkdirs()
        val targetPath = iconDir.toPath().toAbsolutePath().normalize()
        return try {
            ZipFile(pluginApk).use { zip ->
                fun extractEntry(role: String, entryPath: String?): String? {
                    entryPath ?: return null
                    val entry = zip.getEntry(entryPath) ?: return null
                    val ext = entryPath.substringAfterLast('.', "").ifEmpty { "png" }
                    val outPath = targetPath.resolve("$role.$ext").normalize()
                    if (!outPath.startsWith(targetPath)) return null
                    zip.getInputStream(entry).use { input ->
                        outPath.toFile().outputStream().use { output -> input.copyTo(output) }
                    }
                    return outPath.toFile().absolutePath
                }
                extractEntry("icon_day", manifest.iconDay) to extractEntry("icon_night", manifest.iconNight)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract plugin icons for $pluginId", e)
            null to null
        }
    }

    fun getPluginMetadata(): PluginManifest? {
        try {
            val packageInfo = pluginPackageInfo ?: context.packageManager.getPackageArchiveInfo(
                pluginApk.absolutePath,
                PackageManager.GET_META_DATA
            ) ?: return null

            val metaData = packageInfo.applicationInfo?.metaData ?: return null

            // Extract plugin metadata from AndroidManifest meta-data tags
            val pluginId = metaData.getString("plugin.id") ?: packageInfo.packageName
            val pluginName = metaData.getString("plugin.name") ?: packageInfo.applicationInfo?.name ?: "Unknown Plugin"
            val pluginVersion = metaData.getString("plugin.version") ?: "1.0.0"
            val pluginDescription = metaData.getString("plugin.description") ?: ""
            val pluginAuthor = metaData.getString("plugin.author") ?: ""
            val pluginMainClass = metaData.getString("plugin.main_class") ?: return null
            val pluginMinIdeVersion = metaData.getString("plugin.min_ide_version") ?: "1.0.0"
            val pluginMaxIdeVersion = metaData.getString("plugin.max_ide_version")

            // Parse permissions
            val permissions = metaData.getString("plugin.permissions")?.split(",")?.map { it.trim() } ?: emptyList()

            // Parse dependencies
            val dependencies = metaData.getString("plugin.dependencies")?.split(",")?.map { it.trim() } ?: emptyList()

            // Parse sidebar items count
            val sidebarItems = metaData.getInt("plugin.sidebar_items", 0)

            val iconDay = metaData.getString("plugin.icon_day")
            val iconNight = metaData.getString("plugin.icon_night")

            return PluginManifest(
                id = pluginId,
                name = pluginName,
                version = pluginVersion,
                description = pluginDescription,
                author = pluginAuthor,
                mainClass = pluginMainClass,
                minIdeVersion = pluginMinIdeVersion,
                maxIdeVersion = pluginMaxIdeVersion,
                permissions = permissions,
                dependencies = dependencies,
                extensions = emptyList(),
                sidebarItems = sidebarItems,
                iconDay = iconDay,
                iconNight = iconNight
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract plugin metadata: ${e.message}", e)

            // Fallback to JSON manifest if available
            return PluginManifestParser.parseFromJar(pluginApk)
        }
    }

    /**
     * Validate APK signature
     */
    fun validateSignature(): Boolean {
        try {
            @Suppress("DEPRECATION")
            val packageInfo = context.packageManager.getPackageArchiveInfo(
                pluginApk.absolutePath,
                PackageManager.GET_SIGNATURES
            ) ?: return false

            // Basic signature validation - check if APK is signed
            @Suppress("DEPRECATION")
            val signatures = packageInfo.signatures
            return signatures != null && signatures.isNotEmpty()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to validate APK signature: ${e.message}", e)
            return false
        }
    }

    /**
     * Clean up resources
     */
    fun dispose() {
        pluginResources = null
        pluginPackageInfo = null
        pluginClassLoader = null
    }
}
