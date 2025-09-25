package com.itsaky.androidide.plugins.manager.services

import com.itsaky.androidide.plugins.PluginPermission
import com.itsaky.androidide.plugins.services.IdeFileService
import com.itsaky.androidide.utils.Environment
import java.io.File

class IdeFileServiceImpl(
    private val pluginId: String,
    private val permissions: Set<PluginPermission>,
    private val pathValidator: PathValidator? = null
) : IdeFileService {

    interface PathValidator {
        fun isPathAllowed(path: File): Boolean
        fun getAllowedPaths(): List<String>
    }

    private val requiredPermissions = setOf(PluginPermission.FILESYSTEM_WRITE)

    override fun readFile(file: File): String? {
        if (!hasRequiredPermissions()) {
            throw SecurityException("Plugin $pluginId does not have required permissions: ${getRequiredPermissionsString()}")
        }

        if (!isPathAllowed(file)) {
            throw SecurityException("Plugin $pluginId does not have access to path: ${file.absolutePath}")
        }

        return try {
            if (file.exists() && file.isFile) {
                file.readText()
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    override fun writeFile(file: File, content: String): Boolean {
        if (!hasRequiredPermissions()) {
            throw SecurityException("Plugin $pluginId does not have required permissions: ${getRequiredPermissionsString()}")
        }

        if (!isPathAllowed(file)) {
            throw SecurityException("Plugin $pluginId does not have access to path: ${file.absolutePath}")
        }

        return try {
            file.parentFile?.mkdirs()
            file.writeText(content)
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun appendToFile(file: File, content: String): Boolean {
        if (!hasRequiredPermissions()) {
            throw SecurityException("Plugin $pluginId does not have required permissions: ${getRequiredPermissionsString()}")
        }

        if (!isPathAllowed(file)) {
            throw SecurityException("Plugin $pluginId does not have access to path: ${file.absolutePath}")
        }

        return try {
            file.parentFile?.mkdirs()
            file.appendText(content)
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun insertAfterPattern(file: File, pattern: String, content: String): Boolean {
        if (!hasRequiredPermissions()) {
            throw SecurityException("Plugin $pluginId does not have required permissions: ${getRequiredPermissionsString()}")
        }

        if (!isPathAllowed(file)) {
            throw SecurityException("Plugin $pluginId does not have access to path: ${file.absolutePath}")
        }

        return try {
            val fileContent = file.readText()
            val index = fileContent.indexOf(pattern)

            if (index == -1) {
                return false
            }

            val insertionPoint = index + pattern.length
            val newContent = fileContent.substring(0, insertionPoint) + content + fileContent.substring(insertionPoint)

            file.writeText(newContent)
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun replaceInFile(file: File, oldText: String, newText: String): Boolean {
        if (!hasRequiredPermissions()) {
            throw SecurityException("Plugin $pluginId does not have required permissions: ${getRequiredPermissionsString()}")
        }

        if (!isPathAllowed(file)) {
            throw SecurityException("Plugin $pluginId does not have access to path: ${file.absolutePath}")
        }

        return try {
            val fileContent = file.readText()
            val newContent = fileContent.replace(oldText, newText)

            if (fileContent != newContent) {
                file.writeText(newContent)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
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
        pathValidator?.let { validator ->
            return validator.isPathAllowed(path)
        }

        return isPathAllowedDefault(path)
    }

    private fun isPathAllowedDefault(path: File): Boolean {
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
            "/storage/emulated/0/${Environment.PROJECTS_FOLDER}",
            "/sdcard/${Environment.PROJECTS_FOLDER}",
            System.getProperty("user.home", "/") + "/${Environment.PROJECTS_FOLDER}",
            "/tmp/CodeOnTheGoProject"
        )
    }
}