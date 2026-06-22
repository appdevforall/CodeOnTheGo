package com.itsaky.androidide.actions

import com.itsaky.androidide.actions.observers.FileActionObserver

/**
 * DEPRECATED: FileActionManager was used to execute CreateFileCommand, which has been removed.
 * File operations are now performed directly using FileIOUtils in NewFileAction.
 * This class is kept for backward compatibility but is no longer actively used.
 */
@Deprecated("Use direct file operations with FileIOUtils instead")
class FileActionManager {
    @Deprecated("File commands have been removed. Use FileIOUtils directly.")
    fun execute(observer: FileActionObserver? = null) {
        observer?.onActionFailure("File action manager is deprecated and no longer supported")
    }
}