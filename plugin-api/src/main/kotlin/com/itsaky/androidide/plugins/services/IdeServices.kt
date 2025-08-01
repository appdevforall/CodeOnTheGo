
package com.itsaky.androidide.plugins.services

import android.app.Activity
import com.itsaky.androidide.plugins.extensions.IProject
import java.io.File

/**
 * Service interface that provides access to COGO project information.
 * This service should be registered by AndroidIDE and made available to plugins
 * that have the FILESYSTEM_READ permission.
 */
interface IdeProjectService {
    /**
     * Gets the currently active/open project.
     * @return The current project, or null if no project is open
     */
    fun getCurrentProject(): IProject?
    
    /**
     * Gets all projects currently loaded in the IDE.
     * @return List of all loaded projects
     */
    fun getAllProjects(): List<IProject>
    
    /**
     * Finds a project by its root directory path.
     * @param path The root directory path of the project
     * @return The project at the given path, or null if not found
     */
    fun getProjectByPath(path: File): IProject?
}

/**
 * Service interface that provides access to COGO editor state and open files.
 * This service should be registered by COGO and made available to plugins
 * that have the FILESYSTEM_READ permission.
 */
interface IdeEditorService {
    /**
     * Gets the currently active/focused file in the editor.
     * @return The current file, or null if no file is open
     */
    fun getCurrentFile(): File?
    
    /**
     * Gets all files currently open in editor tabs.
     * @return List of all open files
     */
    fun getOpenFiles(): List<File>
    
    /**
     * Checks if a specific file is currently open in the editor.
     * @param file The file to check
     * @return true if the file is open, false otherwise
     */
    fun isFileOpen(file: File): Boolean
    
    /**
     * Gets the currently selected text in the active editor.
     * @return The selected text, or null if no text is selected
     */
    fun getCurrentSelection(): String?
}

/**
 * Service interface that provides access to COGO's UI context for dialogs and UI operations.
 * This service should be registered by COGO and made available to plugins
 * that need to show dialogs or perform UI operations.
 */
interface IdeUIService {
    /**
     * Gets the current Activity context that can be used for showing dialogs.
     * @return The current Activity, or null if no activity is available
     */
    fun getCurrentActivity(): Activity?
    
    /**
     * Checks if UI operations are currently possible.
     * @return true if UI operations can be performed, false otherwise
     */
    fun isUIAvailable(): Boolean
}

/**
 * Service interface that provides access to COGO's build system status and operations.
 * This service should be registered by COGO and made available to plugins
 * that need to monitor build status or trigger builds.
 */
interface IdeBuildService {
    /**
     * Checks if a build/sync operation is currently in progress.
     * @return true if a build is running, false otherwise
     */
    fun isBuildInProgress(): Boolean
    
    /**
     * Checks if the Gradle tooling server is started and ready.
     * @return true if the tooling server is available, false otherwise
     */
    fun isToolingServerStarted(): Boolean
    
    /**
     * Registers a callback to be notified when build status changes.
     * @param callback The callback to register
     */
    fun addBuildStatusListener(callback: BuildStatusListener)
    
    /**
     * Unregisters a build status callback.
     * @param callback The callback to unregister
     */
    fun removeBuildStatusListener(callback: BuildStatusListener)
}

/**
 * Callback interface for build status changes.
 */
interface BuildStatusListener {
    /**
     * Called when a build starts.
     */
    fun onBuildStarted()
    
    /**
     * Called when a build finishes successfully.
     */
    fun onBuildFinished()
    
    /**
     * Called when a build fails or is cancelled.
     * @param error The error message, or null if cancelled
     */
    fun onBuildFailed(error: String?)
}