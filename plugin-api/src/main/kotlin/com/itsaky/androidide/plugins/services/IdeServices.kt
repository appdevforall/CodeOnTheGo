
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
 * Service interface that provides file editing capabilities for plugins.
 * This service should be registered by COGO and made available to plugins
 * that have the FILESYSTEM_WRITE permission.
 */
interface IdeFileService {
    /**
     * Reads the entire content of a file.
     * @param file The file to read
     * @return The file content as a string, or null if the file cannot be read
     */
    fun readFile(file: File): String?

    /**
     * Writes content to a file, replacing any existing content.
     * @param file The file to write to
     * @param content The content to write
     * @return true if the write operation was successful, false otherwise
     */
    fun writeFile(file: File, content: String): Boolean

    /**
     * Appends content to the end of a file.
     * @param file The file to append to
     * @param content The content to append
     * @return true if the append operation was successful, false otherwise
     */
    fun appendToFile(file: File, content: String): Boolean

    /**
     * Inserts content after the first occurrence of a pattern in a file.
     * @param file The file to modify
     * @param pattern The pattern to search for
     * @param content The content to insert after the pattern
     * @return true if the insertion was successful, false otherwise
     */
    fun insertAfterPattern(file: File, pattern: String, content: String): Boolean

    /**
     * Replaces all occurrences of old text with new text in a file.
     * @param file The file to modify
     * @param oldText The text to replace
     * @param newText The replacement text
     * @return true if the replacement was successful, false otherwise
     */
    fun replaceInFile(file: File, oldText: String, newText: String): Boolean
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