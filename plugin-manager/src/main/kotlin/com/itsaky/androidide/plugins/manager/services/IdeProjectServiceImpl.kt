

package com.itsaky.androidide.plugins.manager.services

import android.util.Log
import com.itsaky.androidide.plugins.PluginPermission
import com.itsaky.androidide.plugins.extensions.IProject
import com.itsaky.androidide.plugins.manager.core.PluginManager
import com.itsaky.androidide.plugins.services.IdeProjectService
import com.itsaky.androidide.preferences.internal.GeneralPreferences
import com.itsaky.androidide.projects.ProjectManagerImpl
import com.itsaky.androidide.utils.Environment
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
        Log.d(TAG, "[HOST] openProject requested: ${projectDir.absolutePath}")

        if (!hasRequiredPermissions()) {
            Log.w(TAG, "[HOST] openProject denied: missing permissions ${getRequiredPermissionsString()}")
            throw SecurityException("Plugin $pluginId does not have required permissions: ${getRequiredPermissionsString()}")
        }

        if (!isUnderProjectsDir(projectDir)) {
            Log.w(TAG, "[HOST] openProject denied: ${projectDir.absolutePath} is not under projects dir ${Environment.PROJECTS_DIR?.absolutePath}")
            throw SecurityException("Plugin $pluginId may only open projects under ${Environment.PROJECTS_DIR?.absolutePath}")
        }

        if (!projectDir.exists() || !projectDir.isDirectory) {
            Log.w(TAG, "[HOST] openProject aborted: not a directory (exists=${projectDir.exists()}, isDir=${projectDir.isDirectory})")
            return false
        }

        val activity = activityProvider?.getCurrentActivity()
        if (activity == null) {
            Log.w(TAG, "[HOST] openProject aborted: no foreground activity available")
            return false
        }

        return try {
            ProjectManagerImpl.getInstance().projectPath = projectDir.absolutePath
            GeneralPreferences.lastOpenedProject = projectDir.absolutePath

            // The editor activity is launchMode=singleTask, so re-launching it only delivers
            // onNewIntent (no reload). Recreating it re-runs onCreate, which loads the project
            // from the projectPath we just set — the same effect as the IDE's own project switch.
            activity.runOnUiThread { activity.recreate() }
            Log.d(TAG, "[HOST] openProject: set projectPath and recreated ${activity.javaClass.simpleName} for ${projectDir.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "[HOST] openProject: failed ${e.javaClass.simpleName}: ${e.message}", e)
            false
        }
    }

    private fun isUnderProjectsDir(path: File): Boolean {
        val projectsDir = runCatching { Environment.PROJECTS_DIR }.getOrNull() ?: return false
        return runCatching {
            val base = projectsDir.canonicalFile
            val target = path.canonicalFile
            target.path == base.path || target.path.startsWith(base.path + File.separator)
        }.getOrDefault(false)
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

    private companion object {
        const val TAG = "PairTrace"
    }
}