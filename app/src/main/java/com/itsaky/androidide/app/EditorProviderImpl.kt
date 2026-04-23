package com.itsaky.androidide.app

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.lifecycleScope
import com.itsaky.androidide.activities.editor.EditorHandlerActivity
import com.itsaky.androidide.models.Position
import com.itsaky.androidide.models.Range
import com.itsaky.androidide.models.SaveResult
import com.itsaky.androidide.plugins.manager.services.IdeEditorServiceImpl
import com.itsaky.androidide.plugins.services.CursorPosition
import com.itsaky.androidide.plugins.services.SelectionRange
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.widget.CodeEditor
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Bridges the IDE editor surface (an [EditorHandlerActivity] and its underlying Sora
 * `CodeEditor`s) to the plugin-facing `IdeEditorService.EditorProvider` contract.
 *
 * Lifecycle: created and registered by the editor activity in `onCreate`, and the activity
 * detaches it in `onDestroy` by calling `setEditorProvider(null)` on `PluginManager`.
 *
 * Activity reference is held weakly so a leaked provider can never keep the activity alive.
 */
class EditorProviderImpl(
    activity: EditorHandlerActivity,
) : IdeEditorServiceImpl.EditorProvider {

    private val activityRef = WeakReference(activity)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val fileCallbacks = java.util.concurrent.CopyOnWriteArrayList<(File?) -> Unit>()

    private val internalListener: (File?) -> Unit = { file ->
        fileCallbacks.forEach { cb ->
            try {
                cb(file)
            } catch (_: Exception) {
            }
        }
    }

    init {
        EditorEvents.addFileChangeListener(internalListener)
    }

    /**
     * Detaches from EditorEvents and clears any plugin-registered callbacks. Called by
     * the activity in `onDestroy`.
     */
    fun dispose() {
        EditorEvents.removeFileChangeListener(internalListener)
        fileCallbacks.clear()
        activityRef.clear()
    }

    private fun activity(): EditorHandlerActivity? = activityRef.get()?.takeIf { !it.isDestroyed }

    // --- File state ---------------------------------------------------------

    override fun getCurrentFile(): File? {
        val activity = activity() ?: return null
        val direct = activity.editorViewModel.getCurrentFile()
        if (direct != null) return direct

        // Active tab may be a plugin tab; fall back to the last real file we saw,
        // but only if it's still actually open.
        val fallback = EditorEvents.lastActiveFile ?: return null
        val opened = activity.editorViewModel.getOpenedFiles()
        val target = fallback.absolutePath
        return if (opened.any { it.absolutePath == target }) fallback else null
    }

    override fun getOpenFiles(): List<File> =
        activity()?.editorViewModel?.getOpenedFiles() ?: emptyList()

    override fun isFileOpen(file: File): Boolean {
        val opened = activity()?.editorViewModel?.getOpenedFiles() ?: return false
        val target = file.absolutePath
        return opened.any { it.absolutePath == target }
    }

    override fun isFileModified(file: File): Boolean =
        activity()?.getEditorForFile(file)?.isModified == true

    override fun getModifiedFiles(): List<File> {
        val activity = activity() ?: return emptyList()
        return activity.editorViewModel.getOpenedFiles()
            .filter { activity.getEditorForFile(it)?.isModified == true }
    }

    // --- Cursor / selection / line text ------------------------------------

    // When a plugin tab is on top, `getCurrentEditor()` is null; fall back to the editor
    // for the last real file so plugins can still inspect cursor/selection/content.
    private fun inspectableEditor(): CodeEditor? {
        val activity = activity() ?: return null
        activity.getCurrentEditor()?.editor?.let { return it }
        val file = getCurrentFile() ?: return null
        return activity.getEditorForFile(file)?.editor
    }

    override fun getCurrentSelection(): String? {
        val editor = inspectableEditor() ?: return null
        val cursor = editor.cursor
        if (!cursor.isSelected) return null
        return editor.text.subSequence(cursor.left, cursor.right).toString()
    }

    override fun getCurrentFileContent(): String? =
        inspectableEditor()?.text?.toString()

    override fun getFileContent(file: File): String? =
        activity()?.getEditorForFile(file)?.editor?.text?.toString()

    override fun getCurrentCursorPosition(): CursorPosition? {
        val editor = inspectableEditor() ?: return null
        val cursor = editor.cursor
        return CursorPosition(cursor.leftLine, cursor.leftColumn, cursor.left)
    }

    override fun getCurrentSelectionRange(): SelectionRange? {
        val editor = inspectableEditor() ?: return null
        val cursor = editor.cursor
        if (!cursor.isSelected) return null
        return SelectionRange(cursor.leftLine, cursor.leftColumn, cursor.rightLine, cursor.rightColumn)
    }

    override fun getCurrentLineText(): String? {
        val editor = inspectableEditor() ?: return null
        val line = editor.cursor.leftLine
        val text = editor.text
        if (line !in 0 until text.lineCount) return null
        return text.getLine(line).toString()
    }

    override fun getLineText(file: File, lineNumber: Int): String? {
        val editor = activity()?.getEditorForFile(file)?.editor ?: return null
        val text = editor.text
        if (lineNumber !in 0 until text.lineCount) return null
        return text.getLine(lineNumber).toString()
    }

    override fun getLineCount(file: File): Int =
        activity()?.getEditorForFile(file)?.editor?.text?.lineCount ?: 0

    override fun getWordAtCursor(): String? {
        val editor = inspectableEditor() ?: return null
        val cursor = editor.cursor
        val text = editor.text
        val line = cursor.leftLine
        if (line !in 0 until text.lineCount) return null
        val lineText = text.getLine(line).toString()
        val column = cursor.leftColumn.coerceIn(0, lineText.length)
        var start = column
        while (start > 0 && lineText[start - 1].isWordChar()) start--
        var end = column
        while (end < lineText.length && lineText[end].isWordChar()) end++
        if (start == end) return null
        return lineText.substring(start, end)
    }

    override fun getCurrentLanguageId(): String? =
        getCurrentFile()?.let { languageIdForFile(it) }

    override fun getFileLanguageId(file: File): String? = languageIdForFile(file)

    // --- Tab control --------------------------------------------------------

    override fun openFile(file: File): Boolean {
        val activity = activity() ?: return false
        activity.openFileAsync(file) {}
        return true
    }

    override fun openFileAt(file: File, line: Int, column: Int): Boolean {
        val activity = activity() ?: return false
        val pos = Position(line.coerceAtLeast(0), column.coerceAtLeast(0))
        activity.openFileAndSelect(file, Range(pos, pos))
        return true
    }

    override fun saveCurrentFile(): Boolean {
        val activity = activity() ?: return false
        val index = activity.editorViewModel.getCurrentFileIndex()
        if (index < 0) return false
        activity.lifecycleScope.launch {
            activity.saveResult(index, SaveResult())
        }
        return true
    }

    // --- Buffer edits -------------------------------------------------------

    override fun insertTextAtCursor(text: String): Boolean = onMain {
        val editor = inspectableEditor() ?: return@onMain false
        val cursor = editor.cursor
        editor.text.runEdit {
            if (cursor.isSelected) {
                replace(cursor.leftLine, cursor.leftColumn, cursor.rightLine, cursor.rightColumn, text)
            } else {
                insert(cursor.leftLine, cursor.leftColumn, text)
            }
        }
        true
    }

    override fun replaceSelection(text: String): Boolean = onMain {
        val editor = inspectableEditor() ?: return@onMain false
        val cursor = editor.cursor
        if (!cursor.isSelected) return@onMain false
        editor.text.runEdit {
            replace(cursor.leftLine, cursor.leftColumn, cursor.rightLine, cursor.rightColumn, text)
        }
        true
    }

    override fun appendToLine(file: File, line: Int, text: String): Boolean =
        lineEdit(file, line, existing = true) { insert(line, getColumnCount(line), text) }

    override fun prependToLine(file: File, line: Int, text: String): Boolean =
        lineEdit(file, line, existing = true) { insert(line, 0, text) }

    override fun replaceLine(file: File, line: Int, newText: String): Boolean =
        lineEdit(file, line, existing = true) { replace(line, 0, line, getColumnCount(line), newText) }

    override fun insertLineBefore(file: File, line: Int, text: String): Boolean {
        val payload = if (text.endsWith("\n")) text else "$text\n"
        return lineEdit(file, line, existing = false) { insert(line, 0, payload) }
    }

    override fun deleteLine(file: File, line: Int): Boolean =
        lineEdit(file, line, existing = true) {
            if (line < lineCount - 1) {
                delete(line, 0, line + 1, 0)
            } else if (line > 0) {
                delete(line - 1, getColumnCount(line - 1), line, getColumnCount(line))
            } else {
                delete(line, 0, line, getColumnCount(line))
            }
        }

    override fun replaceRange(file: File, range: SelectionRange, newText: String): Boolean = onMain {
        val editor = activity()?.getEditorForFile(file)?.editor ?: return@onMain false
        val content = editor.text
        val maxLine = content.lineCount - 1
        if (range.startLine !in 0..maxLine || range.endLine !in 0..maxLine) return@onMain false
        content.runEdit {
            replace(range.startLine, range.startColumn, range.endLine, range.endColumn, newText)
        }
        true
    }

    /**
     * Resolves the editor for [file], validates [line] (bounds differ between edits that
     * mutate an existing line and those that insert a new one), and runs [block] inside a
     * single batched edit on the main thread. `existing = true` requires 0 ≤ line < lineCount;
     * `existing = false` allows line == lineCount for "insert at end".
     */
    private inline fun lineEdit(
        file: File,
        line: Int,
        existing: Boolean,
        crossinline block: Content.() -> Unit,
    ): Boolean = onMain {
        val editor = activity()?.getEditorForFile(file)?.editor ?: return@onMain false
        val content = editor.text
        val valid = if (existing) line in 0 until content.lineCount else line in 0..content.lineCount
        if (!valid) return@onMain false
        content.runEdit(block)
        true
    }


    override fun addFileChangeCallback(callback: (File?) -> Unit) {
        fileCallbacks.addIfAbsent(callback)
    }

    override fun removeFileChangeCallback(callback: (File?) -> Unit) {
        fileCallbacks.remove(callback)
    }

    // --- Helpers ------------------------------------------------------------

    private inline fun <T> Content.runEdit(block: Content.() -> T): T {
        beginBatchEdit()
        try {
            return block()
        } finally {
            endBatchEdit()
        }
    }

    /**
     * Posts [block] to the main thread and blocks the caller until it finishes. If the main
     * thread doesn't process the edit within [MAIN_EDIT_TIMEOUT_SECONDS] the call logs a
     * warning and returns `false` rather than hanging the plugin's thread or throwing
     * through to an uncaught-exception handler — a deadlocked UI should not be able to take
     * the IDE down with it.
     */
    private inline fun onMain(crossinline block: () -> Boolean): Boolean {
        if (Looper.myLooper() === mainHandler.looper) return block()
        val latch = CountDownLatch(1)
        val resultRef = AtomicReference(false)
        val errorRef = AtomicReference<Throwable?>(null)
        mainHandler.post {
            try {
                resultRef.set(block())
            } catch (t: Throwable) {
                errorRef.set(t)
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(MAIN_EDIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            log.warn(
                "Main thread did not process plugin edit within {}s; aborting",
                MAIN_EDIT_TIMEOUT_SECONDS,
            )
            return false
        }
        errorRef.get()?.let { throw it }
        return resultRef.get()
    }

    private fun Char.isWordChar(): Boolean = isLetterOrDigit() || this == '_'

    private fun languageIdForFile(file: File): String? {
        val ext = file.extension.lowercase()
        return when (ext) {
            "" -> null
            "kt", "kts" -> "kotlin"
            "java" -> "java"
            "xml" -> "xml"
            "json" -> "json"
            "gradle" -> "groovy"
            "groovy" -> "groovy"
            "md", "markdown" -> "markdown"
            "yml", "yaml" -> "yaml"
            "properties" -> "properties"
            "sh", "bash" -> "shell"
            "c" -> "c"
            "cpp", "cc", "cxx", "h", "hpp" -> "cpp"
            "py" -> "python"
            "js" -> "javascript"
            "ts" -> "typescript"
            "html", "htm" -> "html"
            "css" -> "css"
            else -> ext
        }
    }

    companion object {
        private const val MAIN_EDIT_TIMEOUT_SECONDS = 5L
        private val log = LoggerFactory.getLogger(EditorProviderImpl::class.java)
    }
}
