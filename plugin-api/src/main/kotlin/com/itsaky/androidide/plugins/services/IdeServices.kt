
package com.itsaky.androidide.plugins.services

import android.app.Activity
import com.itsaky.androidide.plugins.extensions.IProject
import java.io.File
import java.io.InputStream
import java.util.concurrent.CompletableFuture

/**
 * Service interface that provides access to Code On the Go project information.
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

    /**
     * Resolves the build context (compile/intermediate classpaths, runtime dex files,
     * selected variant, resource APK, and whether a build is needed) for the module that
     * owns the given file.
     *
     * Defaults to returning null so the method is binary-compatible: hosts that predate it,
     * and implementors that do not override it, report "unavailable" (mirrors the default on
     * [IdeUIService.openPluginScreen]).
     *
     * @param filePath The absolute path of a source file owned by the module
     * @return The module context, or null if no module can be resolved
     */
    fun getModuleContext(filePath: String): ModuleContext? = null
}

/**
 * 0-based cursor position inside an editor buffer.
 */
data class CursorPosition(val line: Int, val column: Int, val index: Int)

/**
 * 0-based selection range. Inclusive of start, exclusive of end (matches the underlying editor).
 */
data class SelectionRange(
    val startLine: Int,
    val startColumn: Int,
    val endLine: Int,
    val endColumn: Int,
)

/**
 * Notified when the user switches between open files. The new active file is passed,
 * or null if all files are closed.
 */
fun interface FileChangeListener {
    fun onFileChanged(file: File?)
}

/**
 * Service interface that provides access to Code On the Go editor state and open files.
 * Read methods require FILESYSTEM_READ. Methods that mutate editor state (open/save) require
 * FILESYSTEM_WRITE.
 */
interface IdeEditorService {
    fun getCurrentFile(): File?

    fun getOpenFiles(): List<File>

    fun isFileOpen(file: File): Boolean

    fun getCurrentSelection(): String?

    fun getCurrentFileContent(): String?

    fun getFileContent(file: File): String?

    fun getCurrentCursorPosition(): CursorPosition?

    fun getCurrentSelectionRange(): SelectionRange?

    fun getCurrentLineText(): String?

    fun getLineText(file: File, lineNumber: Int): String?

    fun getLineCount(file: File): Int

    fun getWordAtCursor(): String?

    fun getCurrentLanguageId(): String?

    fun getFileLanguageId(file: File): String?

    fun isFileModified(file: File): Boolean

    fun getModifiedFiles(): List<File>

    /**
     * Schedules the given file to be opened in the editor. The open itself runs asynchronously
     * on the IDE's editor thread — a `true` return means the request was dispatched, not that
     * the file is already open or that it exists, is readable, or was handled by this IDE
     * rather than delegated (image viewer, another plugin, etc.). Poll [isFileOpen] if you
     * need to confirm completion.
     */
    fun openFile(file: File): Boolean

    /** See [openFile]. The caret is moved to the given 0-based position once the open completes. */
    fun openFileAt(file: File, line: Int, column: Int): Boolean

    /**
     * Schedules a save of the active editor tab. Runs asynchronously; a `true` return means
     * the save was dispatched, not that the buffer has been flushed to disk. Poll
     * [isFileModified] on the current file to confirm completion.
     */
    fun saveCurrentFile(): Boolean

    fun insertTextAtCursor(text: String): Boolean

    fun replaceSelection(text: String): Boolean

    fun appendToLine(file: File, line: Int, text: String): Boolean

    fun prependToLine(file: File, line: Int, text: String): Boolean

    fun replaceLine(file: File, line: Int, newText: String): Boolean

    fun insertLineBefore(file: File, line: Int, text: String): Boolean

    fun deleteLine(file: File, line: Int): Boolean

    fun replaceRange(file: File, range: SelectionRange, newText: String): Boolean

    fun addFileChangeListener(listener: FileChangeListener)

    fun removeFileChangeListener(listener: FileChangeListener)
}

/**
 * Service interface that provides access to Code On the Go's UI context for dialogs and UI operations.
 * This service should be registered by Code On the Go and made available to plugins
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

    /**
     * Opens a fullscreen host surface for a plugin-owned Fragment.
     *
     * The host app owns only the generic container. The plugin owns the Fragment class and all
     * feature-specific behavior.
     */
    fun openPluginScreen(
        pluginId: String,
        fragmentClassName: String,
        title: String? = null
    ): Boolean = false

    companion object {
        const val ACTION_OPEN_PLUGIN_SCREEN = "com.itsaky.androidide.plugins.OPEN_PLUGIN_SCREEN"
        const val EXTRA_PLUGIN_ID = "com.itsaky.androidide.plugins.extra.PLUGIN_ID"
        const val EXTRA_FRAGMENT_CLASS_NAME = "com.itsaky.androidide.plugins.extra.FRAGMENT_CLASS_NAME"
        const val EXTRA_TITLE = "com.itsaky.androidide.plugins.extra.TITLE"
    }
}

/**
 * Service interface that provides access to Code On the Go's build system status and operations.
 * This service should be registered by Code On the Go and made available to plugins
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

    /**
     * Executes the given Gradle task paths (e.g. ":app:assembleDebug") and completes with
     * true on success, false on failure/cancellation.
     *
     * Default completes with false so this addition is binary-compatible: hosts that predate
     * the method, and any implementor that does not override it, report "not executed".
     */
    fun executeTasks(vararg tasks: String): CompletableFuture<Boolean> =
        CompletableFuture.completedFuture(false)

    /**
     * Builds and runs the app on the connected device.
     * @param callback The callback to be invoked when the operation completes
     */
    fun runApp(callback: BuildAndLaunchCallback) {
        callback.onComplete(false, "Not implemented")
    }

    /**
     * Triggers a Gradle sync operation.
     * @param callback The callback to be invoked when the sync completes
     */
    fun triggerGradleSync(callback: GradleSyncCallback) {
        callback.onComplete(false, "")
    }

    /**
     * Gets the latest build output logs.
     * @return The build output as a string, or null if no build output is available
     */
    fun getBuildOutput(): String? = null
}

/**
 * Callback interface for build and launch operations.
 */
fun interface BuildAndLaunchCallback {
    /**
     * Called when the build and launch operation completes.
     * @param success true if the operation succeeded, false otherwise
     * @param message A message describing the result
     */
    fun onComplete(success: Boolean, message: String)
}

/**
 * Callback interface for Gradle sync operations.
 */
fun interface GradleSyncCallback {
    /**
     * Called when the Gradle sync operation completes.
     * @param success true if the sync succeeded, false otherwise
     * @param output The sync output
     */
    fun onComplete(success: Boolean, output: String)
}

/**
 * Service interface that provides file editing capabilities for plugins.
 * This service should be registered by Code On the Go and made available to plugins
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

    /**
     * Writes binary content to a file, replacing any existing content.
     * Use this instead of [writeFile] for non-text data: UTF-8 transcoding in
     * [writeFile] corrupts arbitrary bytes.
     * @param file The file to write to
     * @param data The bytes to write
     * @return true if the write operation was successful, false otherwise
     */
    fun writeBinary(file: File, data: ByteArray): Boolean

    /**
     * Writes content from an input stream to a file, replacing any existing
     * content. Preferred for large payloads (archives, toolchain assets) since
     * no intermediate buffer of the full payload is held in memory.
     *
     * The caller owns [input] and is responsible for closing it.
     * @param file The file to write to
     * @param input The stream to read from
     * @return The number of bytes written, or -1 if the operation failed
     */
    fun writeStream(file: File, input: InputStream): Long

    /**
     * Deletes a file or directory. Directories are removed recursively.
     * Required for plugins that need to clean up installed assets in
     * [com.itsaky.androidide.plugins.IPlugin.deactivate].
     * @param file The file or directory to delete
     * @return true if the deletion was successful, false otherwise
     */
    fun delete(file: File): Boolean

    /**
     * Lists files in a directory.
     * @param dir The directory to list (or null for project root)
     * @param recursive Whether to list recursively
     * @return List of files, or empty list if the directory cannot be read
     */
    fun listFiles(dir: File?, recursive: Boolean = false): List<File>
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

/**
 * Service interface that provides project modification capabilities for plugins.
 *
 * This service is separate from [IdeProjectService] (which provides read-only project
 * information) to distinguish between project introspection and project modification.
 * Plugins that need to add dependencies, create resources, or delete files should use this service.
 *
 * This service should be registered by Code On the Go and made available to plugins
 * that have the PROJECT_STRUCTURE permission and need to modify project files
 * (build files, resources, etc.).
 */
interface IdeProjectManipulationService {
    /**
     * Adds a dependency to a Gradle build file.
     * @param dependencyString The dependency line including configuration, e.g., 'implementation("io.coil-kt:coil:2.6.0")'
     * @param buildFilePath Relative path to build file, e.g., 'app/build.gradle.kts'
     * @return true if the dependency was added successfully, false otherwise
     */
    fun addDependency(dependencyString: String, buildFilePath: String): Boolean = false

    /**
     * Adds a string resource to the strings.xml file.
     * @param name The resource name, e.g., 'welcome_message'
     * @param value The string content, e.g., 'Hello, World!'
     * @return true if the string resource was added successfully, false otherwise
     */
    fun addStringResource(name: String, value: String): Boolean = false

    /**
     * Deletes a file from the project.
     * @param path The path to the file to delete
     * @return true if the file was deleted successfully, false otherwise
     */
    fun deleteFile(path: String): Boolean = false
}

/**
 * Module context data returned by [IdeProjectService.getModuleContext]. All paths are absolute host paths.
 *
 * This is additive API: it carries the project-model data an on-device compiler/renderer
 * needs (classpaths, runtime dex, selected variant, resource APK) without exposing any
 * host-internal project types to the plugin.
 */
data class ModuleContext(
    val modulePath: String?,
    val variantName: String,
    val compileClasspaths: List<File>,
    val intermediateClasspaths: List<File>,
    val runtimeDexFiles: List<File>,
    val resourceApk: File?,
    val needsBuild: Boolean
)
