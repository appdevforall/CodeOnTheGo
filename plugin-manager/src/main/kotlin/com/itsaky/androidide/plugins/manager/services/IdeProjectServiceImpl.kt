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

package com.itsaky.androidide.plugins.manager.services

import com.itsaky.androidide.plugins.PluginPermission
import com.itsaky.androidide.plugins.extensions.IProject
import com.itsaky.androidide.plugins.services.IdeProjectService
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
            canonicalPath.startsWith(allowedPath)
        }
    }

    private fun getDefaultAllowedPaths(): List<String> {
        return listOf(
            "/storage/emulated/0/AndroidIDEProjects",
            "/sdcard/AndroidIDEProjects",
            System.getProperty("user.home", "/") + "/AndroidIDEProjects",
            "/tmp/AndroidIDEProject" // Allow temporary project for demo purposes
        )
    }
}