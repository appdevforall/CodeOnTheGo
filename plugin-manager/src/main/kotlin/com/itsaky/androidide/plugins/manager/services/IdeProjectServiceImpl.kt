

package com.itsaky.androidide.plugins.manager.services

import android.util.Log
import com.itsaky.androidide.plugins.PluginPermission
import com.itsaky.androidide.plugins.extensions.IProject
import com.itsaky.androidide.plugins.manager.core.PluginManager
import com.itsaky.androidide.plugins.services.IdeProjectService
import com.itsaky.androidide.preferences.internal.GeneralPreferences
import com.itsaky.androidide.projects.ProjectManagerImpl
import com.itsaky.androidide.utils.Environment
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
    private val pathValidator: PathValidator? = null,
    private val activityProvider: PluginManager.ActivityProvider? = null
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

    override fun openProject(projectDir: File): Boolean {
        if (!hasRequiredPermissions()) {
            Log.w(TAG, "openProject denied: missing permissions ${getRequiredPermissionsString()}")
            throw SecurityException("Plugin $pluginId does not have required permissions: ${getRequiredPermissionsString()}")
        }

        // Validate against the canonical, containment-checked target and reuse it everywhere below,
        // so a symlink/relative path can't pass the check as one path yet be switched to as another.
        val resolvedProjectDir = resolveProjectDirUnderProjectsDir(projectDir)
        if (resolvedProjectDir == null) {
            Log.w(TAG, "openProject denied: ${projectDir.absolutePath} is not under projects dir ${Environment.PROJECTS_DIR?.absolutePath}")
            throw SecurityException("Plugin $pluginId may only open projects under ${Environment.PROJECTS_DIR?.absolutePath}")
        }

        // Apply the same path-access policy used by getProjectByPath.
        if (!isPathAllowed(resolvedProjectDir)) {
            throw SecurityException("Plugin $pluginId does not have access to path: ${resolvedProjectDir.absolutePath}")
        }

        if (!resolvedProjectDir.exists() || !resolvedProjectDir.isDirectory) {
            Log.w(TAG, "openProject aborted: not a directory (exists=${resolvedProjectDir.exists()}, isDir=${resolvedProjectDir.isDirectory})")
            return false
        }

        val activity = activityProvider?.getCurrentActivity()
        if (activity == null) {
            Log.w(TAG, "openProject aborted: no foreground activity available")
            return false
        }

        return try {
            ProjectManagerImpl.getInstance().projectPath = resolvedProjectDir.absolutePath
            GeneralPreferences.lastOpenedProject = resolvedProjectDir.absolutePath

            // The editor activity is launchMode=singleTask, so re-launching it only delivers
            // onNewIntent (no reload). Recreating it re-runs onCreate, which loads the project
            // from the projectPath we just set — the same effect as the IDE's own project switch.
            activity.runOnUiThread { activity.recreate() }
            true
        } catch (e: Exception) {
            Log.e(TAG, "openProject failed: ${e.javaClass.simpleName}: ${e.message}", e)
            false
        }
    }

    private fun resolveProjectDirUnderProjectsDir(path: File): File? {
        val projectsDir = runCatching { Environment.PROJECTS_DIR }.getOrNull() ?: return null
        return runCatching {
            val base = projectsDir.canonicalFile
            val target = path.canonicalFile
            target.takeIf { it.path == base.path || it.path.startsWith(base.path + File.separator) }
        }.getOrNull()
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

    private companion object {
        const val TAG = "PairTrace"
    }
}