package com.itsaky.androidide.actions.observers

import java.io.File

/**
 * An observer to receive feedback on file action results.
 * Implement this in your Fragment or Activity to show UI feedback.
 */
interface FileActionObserver {
    fun onActionSuccess(message: String, createdFile: File?)
    fun onActionFailure(errorMessage: String)
}