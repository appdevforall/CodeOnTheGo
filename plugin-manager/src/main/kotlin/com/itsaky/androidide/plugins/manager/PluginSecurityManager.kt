

package com.itsaky.androidide.plugins.manager

import com.itsaky.androidide.plugins.PluginPermission
import java.io.File
import java.security.MessageDigest

class PluginSecurityManager {
    
    // Restricted classes that plugins cannot access
    private val restrictedClasses = setOf(
        "java.lang.Runtime",
        "java.lang.ProcessBuilder",
        "java.lang.System",
        "java.io.File", // Restricted, use ResourceManager instead
        "java.nio.file.Files",
        "java.nio.file.Paths",
        "java.net.Socket",
        "java.net.ServerSocket",
        "java.net.URL", // Restricted without NETWORK_ACCESS permission
        "java.net.URLConnection",
        "java.lang.reflect.Method", // Reflection restrictions
        "java.lang.Class" // Some Class methods restricted
    )
    
    // Restricted packages
    private val restrictedPackages = setOf(
        "sun.",
        "com.sun.",
        "jdk.internal.",
        "java.lang.invoke.",
        "java.security.",
        "javax.security."
    )
    
    fun validatePlugin(pluginFile: File, manifest: PluginManifest): Boolean {
        return try {
            // Check file integrity
            if (!verifyFileIntegrity(pluginFile)) {
                return false
            }
            
            // Validate manifest
            if (!validateManifest(manifest)) {
                return false
            }
            
            // Check permissions are valid
            if (!validatePermissions(manifest.permissions)) {
                return false
            }
            
            true
        } catch (e: Exception) {
            false
        }
    }
    
    fun checkClassAccess(className: String, permissions: Set<PluginPermission>): Boolean {
        // Check if class is in restricted list
        if (restrictedClasses.contains(className)) {
            return hasRequiredPermissionForClass(className, permissions)
        }
        
        // Check if class is in restricted package
        for (restrictedPackage in restrictedPackages) {
            if (className.startsWith(restrictedPackage)) {
                return false
            }
        }
        
        return true
    }
    
    private fun verifyFileIntegrity(file: File): Boolean {
        return try {
            // Basic file validation
            file.exists() && file.isFile && file.canRead()
        } catch (e: Exception) {
            false
        }
    }
    
    private fun validateManifest(manifest: PluginManifest): Boolean {
        return manifest.id.isNotBlank() &&
                manifest.name.isNotBlank() &&
                manifest.version.isNotBlank() &&
                manifest.mainClass.isNotBlank() &&
                manifest.minIdeVersion.isNotBlank()
    }
    
    private fun validatePermissions(permissions: List<String>): Boolean {
        return permissions.all { permission ->
            try {
                PluginPermission.valueOf(permission.uppercase().replace(".", "_"))
                true
            } catch (e: IllegalArgumentException) {
                false
            }
        }
    }
    
    private fun hasRequiredPermissionForClass(className: String, permissions: Set<PluginPermission>): Boolean {
        return when (className) {
            "java.net.URL", "java.net.URLConnection" -> 
                permissions.contains(PluginPermission.NETWORK_ACCESS)
            "java.io.File" -> 
                permissions.contains(PluginPermission.FILESYSTEM_READ) || 
                permissions.contains(PluginPermission.FILESYSTEM_WRITE)
            "java.lang.Runtime", "java.lang.ProcessBuilder" -> 
                permissions.contains(PluginPermission.SYSTEM_COMMANDS)
            else -> false
        }
    }
    
    fun generatePluginHash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = file.readBytes()
        val hash = digest.digest(bytes)
        return hash.joinToString("") { "%02x".format(it) }
    }
}