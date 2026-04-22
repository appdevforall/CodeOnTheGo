package com.itsaky.androidide.plugins.manager.services

import com.itsaky.androidide.plugins.PluginPermission
import com.itsaky.androidide.utils.Environment
import java.io.File

internal object PluginPathAllowlist {

    private const val PLUGIN_DATA_ROOT = "plugins"

    fun isAllowed(path: File, permissions: Set<PluginPermission>, pluginId: String): Boolean {
        val canonical = try {
            path.canonicalPath
        } catch (e: Exception) {
            return false
        }
        return defaultAllowedPaths(permissions, pluginId).any { root -> containsPath(canonical, root) }
    }

    fun defaultAllowedPaths(permissions: Set<PluginPermission>, pluginId: String): List<String> {
        val paths = mutableListOf(
            canonicalOrSelf(File("/storage/emulated/0/${Environment.PROJECTS_FOLDER}")),
            canonicalOrSelf(File("/sdcard/${Environment.PROJECTS_FOLDER}")),
            canonicalOrSelf(File((System.getProperty("user.home") ?: "/") + "/${Environment.PROJECTS_FOLDER}")),
            canonicalOrSelf(File("/tmp/CodeOnTheGoProject"))
        )
        if (PluginPermission.IDE_ENVIRONMENT_WRITE in permissions) {
            paths += canonicalOrSelf(Environment.ANDROID_HOME)
            paths += canonicalOrSelf(Environment.TMP_DIR)
            paths += canonicalOrSelf(File(File(Environment.ANDROIDIDE_HOME, PLUGIN_DATA_ROOT), pluginId))
        }
        return paths
    }

    private fun containsPath(canonical: String, root: String): Boolean {
        val trimmedRoot = root.trimEnd(File.separatorChar)
        return canonical == trimmedRoot || canonical.startsWith(trimmedRoot + File.separatorChar)
    }

    private fun canonicalOrSelf(file: File): String = try {
        file.canonicalPath
    } catch (e: Exception) {
        file.absolutePath
    }
}
