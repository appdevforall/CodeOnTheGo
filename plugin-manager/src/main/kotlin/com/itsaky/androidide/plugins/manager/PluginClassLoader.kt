

package com.itsaky.androidide.plugins.manager

import android.util.Log
import com.itsaky.androidide.plugins.PluginPermission
import dalvik.system.DexClassLoader
import java.io.File
import java.net.URLClassLoader

class PluginClassLoader(
    private val pluginJar: File,
    parentClassLoader: ClassLoader,
    private val permissions: Set<PluginPermission>
) : ClassLoader(parentClassLoader) {
    
    private val dexClassLoader: DexClassLoader
    private val urlClassLoader: URLClassLoader
    private val securityManager = PluginSecurityManager()
    
    // Allowed packages that plugins can access
    private val allowedPackages = setOf(
        "com.itsaky.androidide.plugins",
        "com.itsaky.androidide.eventbus",
        "com.itsaky.androidide.utils",
        "com.itsaky.androidide.editor.api",
        "com.itsaky.androidide.lsp",
        "com.itsaky.androidide.projects.api",
        "java.",
        "javax.",
        "kotlin.",
        "kotlinx.",
        "android.",
        "androidx.",
        "org.json", // For JSON parsing
        "com.google.gson" // For Gson serialization
    )
    
    init {
        val optimizedDir = File(pluginJar.parentFile, "dex_cache")
        if (!optimizedDir.exists()) {
            optimizedDir.mkdirs()
        }
        
        Log.i("PluginClassLoader", "Initializing for ${pluginJar.name}")
        Log.i("PluginClassLoader", "Parent classloader: ${parentClassLoader.javaClass.name}")
        
        dexClassLoader = DexClassLoader(
            pluginJar.absolutePath,
            optimizedDir.absolutePath,
            null,
            parentClassLoader
        )
        
        urlClassLoader = URLClassLoader(
            arrayOf(pluginJar.toURI().toURL()),
            parentClassLoader
        )
        
        // Test if parent can load plugin API classes
        try {
            parentClassLoader.loadClass("com.itsaky.androidide.plugins.IPlugin")
            Log.i("PluginClassLoader", "Parent can load IPlugin - plugin API is available")
        } catch (e: Exception) {
            Log.w("PluginClassLoader", "WARNING - Parent cannot load IPlugin: ${e.message}")
            Log.w("PluginClassLoader", "This indicates plugin-api is not properly included in main app")
        }
    }
    
    override fun findClass(name: String): Class<*> {
        Log.d("PluginClassLoader", "findClass called for: $name")
        
        // Plugin API classes should be loaded from parent
        if (name.startsWith("com.itsaky.androidide.plugins")) {
            Log.d("PluginClassLoader", "Delegating plugin API class $name to parent")
            try {
                return parent.loadClass(name)
            } catch (e: ClassNotFoundException) {
                Log.e("PluginClassLoader", "Parent failed to load plugin API class: $name")
                throw e
            }
        }
        
        // Try DEX class loader for plugin classes
        try {
            return dexClassLoader.loadClass(name)
        } catch (e: ClassNotFoundException) {
            // Try URL class loader as fallback
            try {
                return urlClassLoader.loadClass(name)
            } catch (e2: ClassNotFoundException) {
                Log.e("PluginClassLoader", "Both loaders failed for: $name")
                throw ClassNotFoundException("Could not find class: $name", e)
            }
        }
    }
    
    override fun loadClass(name: String): Class<*> {
        // Debug logging for class loading
        Log.d("PluginClassLoader", "Attempting to load class: $name")
        
        // First check if it's allowed
        if (!isClassAccessAllowed(name)) {
            throw ClassNotFoundException("Access to class $name is not allowed for this plugin")
        }
        
        // Use standard parent-first delegation
        try {
            return parent.loadClass(name)
        } catch (e: ClassNotFoundException) {
            // Not found in parent, use our findClass implementation
            return findClass(name)
        }
    }
    
    override fun getResource(name: String): java.net.URL? {
        return urlClassLoader.getResource(name) ?: parent.getResource(name)
    }
    
    override fun getResourceAsStream(name: String): java.io.InputStream? {
        return urlClassLoader.getResourceAsStream(name) ?: parent.getResourceAsStream(name)
    }
    
    private fun isApiOrSystemClass(className: String): Boolean {
        // Plugin API classes should always be loaded from parent
        if (className.startsWith("com.itsaky.androidide.plugins")) {
            return true
        }
        
        // System classes that should be loaded from parent
        val systemPrefixes = listOf(
            "java.",
            "javax.",
            "kotlin.",
            "kotlinx.",
            "android.",
            "androidx.",
            "org.json",
            "com.google.gson"
        )
        
        return systemPrefixes.any { prefix -> className.startsWith(prefix) }
    }
    
    private fun isClassAccessAllowed(className: String): Boolean {
        // Check if the class is in an allowed package
        val isAllowedPackage = allowedPackages.any { allowedPackage ->
            className.startsWith(allowedPackage)
        }
        
        if (!isAllowedPackage) {
            // Check specific security restrictions
            return securityManager.checkClassAccess(className, permissions)
        }
        
        return true
    }
    
    fun hasPermission(permission: PluginPermission): Boolean {
        return permissions.contains(permission)
    }
    
    fun getPermissions(): Set<PluginPermission> {
        return permissions.toSet()
    }
}