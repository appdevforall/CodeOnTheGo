

package com.itsaky.androidide.plugins.manager.services

import com.itsaky.androidide.plugins.PluginPermission
import com.itsaky.androidide.plugins.extensions.IProject
import com.itsaky.androidide.plugins.services.IdeProjectService
import com.itsaky.androidide.plugins.services.ModuleContext
import java.io.File

/**
 * Implementation of IdeProjectService that provides access to AndroidIDE project information
 * with proper permission validation.
 */
class IdeProjectServiceImpl(
    private val pluginId: String,
    private val permissions: Set<PluginPermission>,
    private val projectProvider: ProjectProvider,
    private val requiredPermissions: Set<PluginPermission> = setOf(PluginPermission.FILESYSTEM_READ),
    private val pathValidator: PathValidator? = null
) : IdeProjectService {

    /**
     * Interface for validating project path access
     */
    interface PathValidator {
        fun isPathAllowed(path: File): Boolean
        fun getAllowedPaths(): List<String>
    }

    /**
     * Interface for providing actual project data from AndroidIDE
     */
    interface ProjectProvider {
        fun getCurrentProject(): IProject?
        fun getAllProjects(): List<IProject>
        fun getProjectByPath(path: File): IProject?
    }

    override fun getCurrentProject(): IProject? {
        if (!hasRequiredPermissions()) {
            throw SecurityException("Plugin $pluginId does not have required permissions: ${getRequiredPermissionsString()}")
        }
        
        return try {
            projectProvider.getCurrentProject()
        } catch (e: Exception) {
            // Log error but don't expose internal details
            null
        }
    }

    override fun getAllProjects(): List<IProject> {
        if (!hasRequiredPermissions()) {
            throw SecurityException("Plugin $pluginId does not have required permissions: ${getRequiredPermissionsString()}")
        }
        
        return try {
            projectProvider.getAllProjects()
        } catch (e: Exception) {
            // Log error but don't expose internal details
            emptyList()
        }
    }

    override fun getProjectByPath(path: File): IProject? {
        if (!hasRequiredPermissions()) {
            throw SecurityException("Plugin $pluginId does not have required permissions: ${getRequiredPermissionsString()}")
        }
        
        // Additional security check: ensure the path is not outside allowed directories
        if (!isPathAllowed(path)) {
            throw SecurityException("Plugin $pluginId does not have access to path: ${path.absolutePath}")
        }
        
        return try {
            projectProvider.getProjectByPath(path)
        } catch (e: Exception) {
            // Log error but don't expose internal details
            null
        }
    }

    override fun getModuleContext(filePath: String): ModuleContext? {
        if (!hasRequiredPermissions()) {
            throw SecurityException("Plugin $pluginId does not have required permissions: ${getRequiredPermissionsString()}")
        }

        val path = File(filePath)
        if (!isPathAllowed(path)) {
            throw SecurityException("Plugin $pluginId does not have access to path: ${path.absolutePath}")
        }

        return try {
            ModuleContextResolver.resolve(filePath)
        } catch (e: Exception) {

            null
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        return requiredPermissions.all { permission ->
            permissions.contains(permission)
        }
    }

    private fun getRequiredPermissionsString(): String {
        return requiredPermissions.joinToString(", ") { it.name }
    }

    private fun isPathAllowed(path: File): Boolean {
        // Use custom path validator if provided
        pathValidator?.let { validator ->
            return validator.isPathAllowed(path)
        }
        
        // Fallback to default validation for backward compatibility
        return isPathAllowedDefault(path)
    }

    private fun isPathAllowedDefault(path: File): Boolean {
        // Default allowed paths - this should be replaced by AndroidIDE with actual project paths
        val allowedPaths = getDefaultAllowedPaths()
        
        val canonicalPath = try {
            path.canonicalPath
        } catch (e: Exception) {
            return false
        }
        
        return allowedPaths.any { allowedPath ->
            canonicalPath == allowedPath || canonicalPath.startsWith(allowedPath + File.separator)
        }
    }

    private fun getDefaultAllowedPaths(): List<String> {
        // Derive allowed roots from the official project API (IdeProjectService /
        // ProjectProvider) rather than hardcoding a projects-directory name. CodeOnTheGo
        // stores projects under CodeOnTheGoProjects and a project
        // can live anywhere the user opened it, so the open project's rootDir is the source
        // of truth for what this plugin may read.
        val roots = mutableListOf<File>()
        runCatching {
            projectProvider.getCurrentProject()?.rootDir?.let { roots.add(it) }
            projectProvider.getAllProjects().forEach { roots.add(it.rootDir) }
        }
        return roots.mapNotNull { root -> runCatching { root.canonicalPath }.getOrNull() }
    }
}