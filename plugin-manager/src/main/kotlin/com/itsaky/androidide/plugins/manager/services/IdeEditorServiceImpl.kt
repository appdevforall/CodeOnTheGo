

package com.itsaky.androidide.plugins.manager.services

import android.util.Log
import com.itsaky.androidide.plugins.PluginPermission
import com.itsaky.androidide.plugins.services.CursorPosition
import com.itsaky.androidide.plugins.services.FileChangeListener
import com.itsaky.androidide.plugins.services.IdeEditorService
import com.itsaky.androidide.plugins.services.SelectionRange
import com.itsaky.androidide.utils.Environment
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

class IdeEditorServiceImpl(
    private val pluginId: String,
    private val permissions: Set<PluginPermission>,
    private val editorProvider: EditorProvider,
    private val readPermissions: Set<PluginPermission> = setOf(PluginPermission.FILESYSTEM_READ),
    private val writePermissions: Set<PluginPermission> = setOf(PluginPermission.FILESYSTEM_WRITE),
    private val pathValidator: PathValidator? = null,
) : IdeEditorService {

    interface PathValidator {
        fun isPathAllowed(file: File): Boolean
        fun getAllowedPaths(): List<String>
    }

    interface EditorProvider {
        fun getCurrentFile(): File?
        fun getOpenFiles(): List<File>
        fun isFileOpen(file: File): Boolean
        fun getCurrentSelection(): String?
        fun getCurrentFileContent(): String? = null
        fun getFileContent(file: File): String? = null
        fun getCurrentCursorPosition(): CursorPosition? = null
        fun getCurrentSelectionRange(): SelectionRange? = null
        fun getCurrentLineText(): String? = null
        fun getLineText(file: File, lineNumber: Int): String? = null
        fun getLineCount(file: File): Int = 0
        fun getWordAtCursor(): String? = null
        fun getCurrentLanguageId(): String? = null
        fun getFileLanguageId(file: File): String? = null
        fun isFileModified(file: File): Boolean = false
        fun getModifiedFiles(): List<File> = emptyList()
        fun openFile(file: File): Boolean = false
        fun openFileAt(file: File, line: Int, column: Int): Boolean = false
        fun saveCurrentFile(): Boolean = false
        fun insertTextAtCursor(text: String): Boolean = false
        fun replaceSelection(text: String): Boolean = false
        fun appendToLine(file: File, line: Int, text: String): Boolean = false
        fun prependToLine(file: File, line: Int, text: String): Boolean = false
        fun replaceLine(file: File, line: Int, newText: String): Boolean = false
        fun insertLineBefore(file: File, line: Int, text: String): Boolean = false
        fun deleteLine(file: File, line: Int): Boolean = false
        fun replaceRange(file: File, range: SelectionRange, newText: String): Boolean = false
        fun addFileChangeCallback(callback: (File?) -> Unit) {}
        fun removeFileChangeCallback(callback: (File?) -> Unit) {}
    }

    private val fileChangeListeners = CopyOnWriteArrayList<FileChangeListener>()

    private val internalFileChangeCallback: (File?) -> Unit = { file ->
        fileChangeListeners.forEach { listener ->
            try {
                listener.onFileChanged(file)
            } catch (_: Exception) {
            }
        }
    }

    init {
        editorProvider.addFileChangeCallback(internalFileChangeCallback)
    }

    fun dispose() {
        editorProvider.removeFileChangeCallback(internalFileChangeCallback)
        fileChangeListeners.clear()
    }

    override fun getCurrentFile(): File? {
        requireRead()
        val file = editorProvider.getCurrentFile() ?: return null
        ensureFileAccessible(file)
        return file
    }

    override fun getOpenFiles(): List<File> {
        requireRead()
        return editorProvider.getOpenFiles().filter { isFileAccessAllowed(it) }
    }

    /**
     * Null-safe "what file is the user looking at, and am I allowed to see it?" used by every
     * read method that short-circuits when there's no current file. Assumes the caller already
     * ran [requireRead]. Doesn't log and never throws — that's the whole point: these methods
     * fire constantly and can't afford to run the full public [getCurrentFile] pipeline on
     * each call.
     */
    private fun resolveCurrentFile(): File? {
        val file = editorProvider.getCurrentFile() ?: return null
        return if (isFileAccessAllowed(file)) file else null
    }

    override fun isFileOpen(file: File): Boolean {
        requireRead()
        ensureFileAccessible(file)
        return editorProvider.isFileOpen(file)
    }

    override fun getCurrentSelection(): String? {
        requireRead()
        if (resolveCurrentFile() == null) return null
        return editorProvider.getCurrentSelection()
    }

    override fun getCurrentFileContent(): String? {
        requireRead()
        if (resolveCurrentFile() == null) return null
        return editorProvider.getCurrentFileContent()
    }

    override fun getFileContent(file: File): String? {
        requireRead()
        ensureFileAccessible(file)
        return editorProvider.getFileContent(file)
    }

    override fun getCurrentCursorPosition(): CursorPosition? {
        requireRead()
        if (resolveCurrentFile() == null) return null
        return editorProvider.getCurrentCursorPosition()
    }

    override fun getCurrentSelectionRange(): SelectionRange? {
        requireRead()
        if (resolveCurrentFile() == null) return null
        return editorProvider.getCurrentSelectionRange()
    }

    override fun getCurrentLineText(): String? {
        requireRead()
        if (resolveCurrentFile() == null) return null
        return editorProvider.getCurrentLineText()
    }

    override fun getLineText(file: File, lineNumber: Int): String? {
        requireRead()
        ensureFileAccessible(file)
        return editorProvider.getLineText(file, lineNumber)
    }

    override fun getLineCount(file: File): Int {
        requireRead()
        ensureFileAccessible(file)
        return editorProvider.getLineCount(file)
    }

    override fun getWordAtCursor(): String? {
        requireRead()
        if (resolveCurrentFile() == null) return null
        return editorProvider.getWordAtCursor()
    }

    override fun getCurrentLanguageId(): String? {
        requireRead()
        if (resolveCurrentFile() == null) return null
        return editorProvider.getCurrentLanguageId()
    }

    override fun getFileLanguageId(file: File): String? {
        requireRead()
        ensureFileAccessible(file)
        return editorProvider.getFileLanguageId(file)
    }

    override fun isFileModified(file: File): Boolean {
        requireRead()
        ensureFileAccessible(file)
        return editorProvider.isFileModified(file)
    }

    override fun getModifiedFiles(): List<File> {
        requireRead()
        return editorProvider.getModifiedFiles().filter { isFileAccessAllowed(it) }
    }

    override fun openFile(file: File): Boolean {
        requireWrite()
        ensureFileAccessible(file)
        return editorProvider.openFile(file)
    }

    override fun openFileAt(file: File, line: Int, column: Int): Boolean {
        requireWrite()
        ensureFileAccessible(file)
        return editorProvider.openFileAt(file, line, column)
    }

    override fun saveCurrentFile(): Boolean {
        if (!writableCurrentFile()) return false
        return editorProvider.saveCurrentFile()
    }

    override fun insertTextAtCursor(text: String): Boolean {
        if (!writableCurrentFile()) return false
        return editorProvider.insertTextAtCursor(text)
    }

    override fun replaceSelection(text: String): Boolean {
        if (!writableCurrentFile()) return false
        return editorProvider.replaceSelection(text)
    }

    private fun writableCurrentFile(): Boolean {
        requireWrite()
        val file = editorProvider.getCurrentFile() ?: return false
        return isFileAccessAllowed(file)
    }

    override fun appendToLine(file: File, line: Int, text: String): Boolean {
        requireWrite()
        ensureFileAccessible(file)
        return editorProvider.appendToLine(file, line, text)
    }

    override fun prependToLine(file: File, line: Int, text: String): Boolean {
        requireWrite()
        ensureFileAccessible(file)
        return editorProvider.prependToLine(file, line, text)
    }

    override fun replaceLine(file: File, line: Int, newText: String): Boolean {
        requireWrite()
        ensureFileAccessible(file)
        return editorProvider.replaceLine(file, line, newText)
    }

    override fun insertLineBefore(file: File, line: Int, text: String): Boolean {
        requireWrite()
        ensureFileAccessible(file)
        return editorProvider.insertLineBefore(file, line, text)
    }

    override fun deleteLine(file: File, line: Int): Boolean {
        requireWrite()
        ensureFileAccessible(file)
        return editorProvider.deleteLine(file, line)
    }

    override fun replaceRange(file: File, range: SelectionRange, newText: String): Boolean {
        requireWrite()
        ensureFileAccessible(file)
        return editorProvider.replaceRange(file, range, newText)
    }

    override fun addFileChangeListener(listener: FileChangeListener) {
        requireRead()
        fileChangeListeners.addIfAbsent(listener)
    }

    override fun removeFileChangeListener(listener: FileChangeListener) {
        fileChangeListeners.remove(listener)
    }

    private fun requireRead() {
        if (!hasAll(readPermissions)) {
            throw SecurityException(
                "Plugin $pluginId is missing required permissions: ${readPermissions.joinToString(",") { it.name }}"
            )
        }
    }

    private fun requireWrite() {
        if (!hasAll(writePermissions)) {
            throw SecurityException(
                "Plugin $pluginId is missing required permissions: ${writePermissions.joinToString(",") { it.name }}"
            )
        }
    }

    private fun hasAll(required: Set<PluginPermission>) = required.all { permissions.contains(it) }

    private fun ensureFileAccessible(file: File) {
        if (!isFileAccessAllowed(file)) {
            throw SecurityException("Plugin $pluginId does not have access to file: ${file.absolutePath}")
        }
    }

    private fun isFileAccessAllowed(file: File): Boolean {
        pathValidator?.let { validator ->
            val ok = runCatching { validator.isPathAllowed(file) }.getOrDefault(false)
            if (!ok) {
                Log.d(TAG, "[$pluginId] pathValidator rejected ${file.absolutePath}")
            }
            return ok
        }

        // No validator wired by the host: if the editor itself has this file open,
        // the user is already exposed to it — trust that and allow the read.
        val openInEditor = runCatching { editorProvider.isFileOpen(file) }.getOrDefault(false)
        if (openInEditor) return true

        val allowed = isFileAccessAllowedDefault(file)
        if (!allowed) {
            Log.d(TAG, "[$pluginId] static allowlist rejected ${file.absolutePath}; allowed roots=$defaultAllowedPaths")
        }
        return allowed
    }

    private fun isFileAccessAllowedDefault(file: File): Boolean {
        val canonicalPath = try {
            file.canonicalPath
        } catch (_: Exception) {
            return false
        }
        return defaultAllowedPaths.any { root ->
            canonicalPath == root || canonicalPath.startsWith(root + File.separator)
        }
    }

    // Canonicalised so symlinked roots don't bypass the check; anchored on File.separator at
    // the match site so e.g. "/…/CodeOnTheGoProjects" doesn't also admit
    // "/…/CodeOnTheGoProjectsBackup/".
    private val defaultAllowedPaths: List<String> by lazy {
        val projects = Environment.PROJECTS_FOLDER
        listOf(
            "/storage/emulated/0/$projects",
            "/sdcard/$projects",
            (System.getProperty("user.home") ?: "/") + "/$projects",
            "/tmp/CodeOnTheGoProject",
        ).map { runCatching { File(it).canonicalPath }.getOrDefault(it) }
    }

    companion object {
        private const val TAG = "IdeEditorService"
    }
}
