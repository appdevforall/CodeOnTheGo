

package com.itsaky.androidide.plugins.manager.services

import com.itsaky.androidide.plugins.PluginPermission
import com.itsaky.androidide.plugins.extensions.IProject
import com.itsaky.androidide.plugins.manager.core.PluginManager
import com.itsaky.androidide.plugins.services.IdeProjectService
import com.itsaky.androidide.preferences.internal.GeneralPreferences
import com.itsaky.androidide.projects.ProjectManagerImpl
import com.itsaky.androidide.utils.Environment
import org.slf4j.LoggerFactory
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
	private val activityProvider: PluginManager.ActivityProvider? = null,
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
			log.warn("getCurrentProject failed for plugin {}; reporting no current project", pluginId, e)
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
			log.warn("getAllProjects failed for plugin {}; reporting an empty project list", pluginId, e)
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
			log.warn("getProjectByPath failed for plugin {}; reporting no project at the requested path", pluginId, e)
			null
		}
	}

	override fun openProject(projectDir: File): Boolean {
		if (!hasRequiredPermissions()) {
			log.warn("openProject denied for plugin {}: missing required permissions", pluginId)
			throw SecurityException("Plugin $pluginId does not have required permissions: ${getRequiredPermissionsString()}")
		}

		// Validate against the canonical, containment-checked target and reuse it everywhere below,
		// so a symlink/relative path can't pass the check as one path yet be switched to as another.
		val resolvedProjectDir = resolveProjectDirUnderProjectsDir(projectDir)
		if (resolvedProjectDir == null) {
			log.warn("openProject denied for plugin {}: target is not under the IDE projects directory", pluginId)
			throw SecurityException("Plugin $pluginId may only open projects under the IDE projects directory")
		}

		// Apply the same path-access policy used by getProjectByPath.
		if (!isPathAllowed(resolvedProjectDir)) {
			log.warn("openProject denied for plugin {}: path-access policy rejected the target", pluginId)
			throw SecurityException("Plugin $pluginId does not have access to the requested path")
		}

		if (!resolvedProjectDir.exists() || !resolvedProjectDir.isDirectory) {
			log.warn("openProject aborted for plugin {}: target does not resolve to an existing directory", pluginId)
			return false
		}

		val activity = activityProvider?.getCurrentActivity()
		if (activity == null) {
			log.warn("openProject aborted for plugin {}: no foreground activity available", pluginId)
			return false
		}
		if (activity.isFinishing || activity.isDestroyed) {
			log.warn("openProject aborted for plugin {}: host activity is finishing or destroyed", pluginId)
			return false
		}

		return try {
			// Switch project state on the UI thread, immediately before recreate(), so the write and
			// the reload are atomic with respect to the activity lifecycle: recreate() is a no-op on
			// an activity that is finishing or destroyed, and mutating the path first would leave the
			// IDE pointing at a project nothing ever loaded.
			activity.runOnUiThread {
				if (activity.isFinishing || activity.isDestroyed) {
					log.warn("openProject aborted for plugin {}: host activity died before recreate", pluginId)
					return@runOnUiThread
				}
				runCatching {
					ProjectManagerImpl.getInstance().projectPath = resolvedProjectDir.absolutePath
					GeneralPreferences.lastOpenedProject = resolvedProjectDir.absolutePath

					// The editor activity is launchMode=singleTask, so re-launching it only delivers
					// onNewIntent (no reload). Recreating it re-runs onCreate, which loads the project
					// from the projectPath we just set - the same effect as the IDE's own project switch.
					activity.recreate()
				}.onFailure { log.error("openProject failed for plugin {} while switching projects", pluginId, it) }
			}
			true
		} catch (e: Exception) {
			log.error("openProject failed for plugin {}", pluginId, e)
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

	private fun hasRequiredPermissions(): Boolean =
		requiredPermissions.all { permission ->
			permissions.contains(permission)
		}

	private fun getRequiredPermissionsString(): String = requiredPermissions.joinToString(", ") { it.name }

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

		val canonicalPath =
			try {
				path.canonicalPath
			} catch (e: Exception) {
				return false
			}

		// Anchored on File.separator so an allowed root like ".../CodeOnTheGoProjects" does not
		// also admit a sibling such as ".../CodeOnTheGoProjects_evil".
		return allowedPaths.any { root ->
			canonicalPath == root || canonicalPath.startsWith(root + File.separator)
		}
	}

	// Canonicalised so a symlinked root cannot bypass the containment check by presenting a
	// different textual prefix than the path being tested.
	private fun getDefaultAllowedPaths(): List<String> {
		val projectsDirPaths =
			runCatching { Environment.PROJECTS_DIR }
				.getOrNull()
				?.let { dir ->
					listOfNotNull(dir.absolutePath, runCatching { dir.canonicalPath }.getOrNull())
				}.orEmpty()

		return (
			projectsDirPaths +
				listOf(
					"/storage/emulated/0/CodeOnTheGoProjects",
					"/sdcard/CodeOnTheGoProjects",
					(System.getProperty("user.home") ?: "/") + "/CodeOnTheGoProjects",
					"/tmp/AndroidIDEProject", // Allow temporary project for demo purposes
				)
		).map { runCatching { File(it).canonicalPath }.getOrDefault(it) }
	}

	private companion object {
		private val log = LoggerFactory.getLogger(IdeProjectServiceImpl::class.java)
	}
}
