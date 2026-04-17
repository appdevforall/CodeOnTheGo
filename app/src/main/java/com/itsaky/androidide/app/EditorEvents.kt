package com.itsaky.androidide.app

import android.util.Log
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Process-wide dispatcher that EditorHandlerActivity uses to broadcast editor lifecycle events
 * out to non-activity subscribers (e.g. plugin service providers). Activity-side observers
 * already have direct access to the view model; this exists so cross-process modules don't
 * need to depend on activity internals.
 *
 * `lastActiveFile` retains the most recent non-null active file so a plugin asking
 * "what is the user editing?" still gets a useful answer even when the foreground tab
 * is a plugin tab (which has no file).
 */
object EditorEvents {

    private const val TAG = "EditorEvents"

    @Volatile
    var lastActiveFile: File? = null
        private set

    private val fileChangeListeners = CopyOnWriteArrayList<(File?) -> Unit>()

    fun addFileChangeListener(listener: (File?) -> Unit) {
        fileChangeListeners.addIfAbsent(listener)
    }

    fun removeFileChangeListener(listener: (File?) -> Unit) {
        fileChangeListeners.remove(listener)
    }

    @Volatile
    private var lastNotifiedPath: String? = null

    /**
     * Called by the editor activity whenever the displayed tab changes. `file` is non-null
     * only when the active tab is a real file (not a plugin tab); `lastActiveFile` is only
     * updated for non-null files so consumers can still answer "what was the user editing?"
     * while a plugin tab is in the foreground. Listeners fire only when the active path
     * actually changes — dedup here means a 2Hz-polling plugin doesn't see redundant
     * notifications on every UI tick.
     */
    fun notifyFileChanged(file: File?) {
        if (file != null) {
            lastActiveFile = file
        }
        val path = file?.absolutePath
        if (path == lastNotifiedPath) return
        lastNotifiedPath = path
        Log.d(TAG, "notifyFileChanged -> ${path ?: "<null>"}")
        fileChangeListeners.forEach { listener ->
            try {
                listener(file)
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Called when an opened file is no longer in the editor (closed). If it matches our
     * stashed `lastActiveFile`, clear it so we don't hand plugins a stale path.
     */
    fun notifyFileClosed(file: File) {
        if (lastActiveFile?.absolutePath == file.absolutePath) {
            Log.d(TAG, "notifyFileClosed: clearing lastActiveFile (${file.absolutePath})")
            lastActiveFile = null
            // Clear dedupe state too, otherwise reopening the same path later could be
            // swallowed by notifyFileChanged's "same path" short-circuit.
            lastNotifiedPath = null
        }
    }
}
