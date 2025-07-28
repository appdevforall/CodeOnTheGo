/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.plugins.manager

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
        "androidx."
    )
    
    init {
        val optimizedDir = File(pluginJar.parentFile, "dex_cache")
        if (!optimizedDir.exists()) {
            optimizedDir.mkdirs()
        }
        
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
    }
    
    override fun loadClass(name: String): Class<*> {
        // Check if class access is allowed
        if (!isClassAccessAllowed(name)) {
            throw ClassNotFoundException("Access to class $name is not allowed for this plugin")
        }
        
        // Try to load from parent first (for system classes)
        try {
            return parent.loadClass(name)
        } catch (e: ClassNotFoundException) {
            // Class not found in parent, try to load from plugin
        }
        
        // For plugin classes, try DEX class loader first (Android bytecode)
        // This works best for plugins built with DEX files
        try {
            return dexClassLoader.loadClass(name)
        } catch (dexException: Exception) {
            // Fallback to URL class loader for Java bytecode (legacy plugins)
            try {
                return urlClassLoader.loadClass(name)
            } catch (urlException: Exception) {
                // Both loaders failed
                println("PluginClassLoader: Both loaders failed for class $name")
                println("DexClassLoader: ${dexException.message}")
                println("URLClassLoader: ${urlException.message}")
                throw ClassNotFoundException("Could not load class $name from plugin", dexException)
            }
        }
    }
    
    override fun getResource(name: String): java.net.URL? {
        return urlClassLoader.getResource(name) ?: parent.getResource(name)
    }
    
    override fun getResourceAsStream(name: String): java.io.InputStream? {
        return urlClassLoader.getResourceAsStream(name) ?: parent.getResourceAsStream(name)
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
}