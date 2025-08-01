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
import com.itsaky.androidide.plugins.services.IdeEditorService
import java.io.File

/**
 * Implementation of IdeEditorService that provides access to AndroidIDE editor state
 * with proper permission validation.
 */
class IdeEditorServiceImpl(
    private val pluginId: String,
    private val permissions: Set<PluginPermission>,
    private val editorProvider: EditorProvider,
    private val requiredPermissions: Set<PluginPermission> = setOf(PluginPermission.FILESYSTEM_READ),
    private val pathValidator: PathValidator? = null
) : IdeEditorService {

    /**
     * Interface for validating file access paths
     */
    interface PathValidator {
        fun isPathAllowed(file: File): Boolean
        fun getAllowedPaths(): List<String>
    }

    /**
     * Interface for providing actual editor data from AndroidIDE
     */
    interface EditorProvider {
        fun getCurrentFile(): File?
        fun getOpenFiles(): List<File>
        fun isFileOpen(file: File): Boolean
        fun getCurrentSelection(): String?
    }

    override fun getCurrentFile(): File? {
        if (!hasRequiredPermissions()) {
            throw SecurityException("Plugin $pluginId does not have required permissions: ${getRequiredPermissionsString()}")
        }
        
        return try {
            val currentFile = editorProvider.getCurrentFile()
            
            // Additional security check: ensure file is in allowed directory
            currentFile?.let { file ->
                if (!isFileAccessAllowed(file)) {
                    throw SecurityException("Plugin $pluginId does not have access to file: ${file.absolutePath}")
                }
                file
            }
        } catch (e: SecurityException) {
            throw e
        } catch (e: Exception) {
            // Log error but don't expose internal details
            null
        }
    }

    override fun getOpenFiles(): List<File> {
        if (!hasRequiredPermissions()) {
            throw SecurityException("Plugin $pluginId does not have required permissions: ${getRequiredPermissionsString()}")
        }
        
        return try {
            val openFiles = editorProvider.getOpenFiles()
            
            // Filter files based on access permissions
            openFiles.filter { file ->
                isFileAccessAllowed(file)
            }
        } catch (e: Exception) {
            // Log error but don't expose internal details
            emptyList()
        }
    }

    override fun isFileOpen(file: File): Boolean {
        if (!hasRequiredPermissions()) {
            throw SecurityException("Plugin $pluginId does not have required permissions: ${getRequiredPermissionsString()}")
        }
        
        if (!isFileAccessAllowed(file)) {
            throw SecurityException("Plugin $pluginId does not have access to file: ${file.absolutePath}")
        }
        
        return try {
            editorProvider.isFileOpen(file)
        } catch (e: Exception) {
            // Log error but don't expose internal details
            false
        }
    }

    override fun getCurrentSelection(): String? {
        if (!hasRequiredPermissions()) {
            throw SecurityException("Plugin $pluginId does not have required permissions: ${getRequiredPermissionsString()}")
        }
        
        // Additional check: only allow if current file is accessible
        val currentFile = getCurrentFile()
        if (currentFile == null) {
            return null
        }
        
        return try {
            editorProvider.getCurrentSelection()
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

    private fun isFileAccessAllowed(file: File): Boolean {
        // Use custom path validator if provided
        pathValidator?.let { validator ->
            return validator.isPathAllowed(file)
        }
        
        // Fallback to default validation for backward compatibility
        return isFileAccessAllowedDefault(file)
    }

    private fun isFileAccessAllowedDefault(file: File): Boolean {
        // Default allowed paths - this should be replaced by AndroidIDE with actual project paths
        val allowedPaths = getDefaultAllowedPaths()
        
        val canonicalPath = try {
            file.canonicalPath
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