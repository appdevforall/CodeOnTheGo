package com.itsaky.androidide.dnd

import android.content.ClipData
import android.content.Context
import android.net.Uri
import android.view.View
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import java.io.File
import java.util.Locale

sealed interface FileDragResult {
    data object Started : FileDragResult
    data class Failed(val error: FileDragError) : FileDragResult
}

sealed interface FileDragError {
    data object FileNotFound : FileDragError
    data object NotAFile : FileDragError
    data object SystemRejected : FileDragError
    data class Exception(val throwable: Throwable) : FileDragError
}

class FileDragStarter(
    private val context: Context,
) {

    fun startDrag(sourceView: View, file: File): FileDragResult {
        if (!file.exists()) {
            return FileDragResult.Failed(FileDragError.FileNotFound)
        }

        if (!file.isFile) {
            return FileDragResult.Failed(FileDragError.NotAFile)
        }

        return runCatching {
            val contentUri = buildContentUri(file)
            val mimeType = resolveMimeType(file)
            val clipData = buildClipData(file, contentUri, mimeType)
            val dragShadow = View.DragShadowBuilder(sourceView)

            ViewCompat.startDragAndDrop(
                sourceView,
                clipData,
                dragShadow,
                null,
                DRAG_FLAGS,
            )
        }.fold(
            onSuccess = { started ->
                if (started) FileDragResult.Started
                else FileDragResult.Failed(FileDragError.SystemRejected)
            },
            onFailure = { throwable ->
                FileDragResult.Failed(FileDragError.Exception(throwable))
            },
        )
    }

    private fun buildContentUri(file: File): Uri {
        return FileProvider.getUriForFile(context, fileProviderAuthority, file)
    }

    private fun resolveMimeType(file: File): String {
        val extension = file.extension.lowercase(Locale.ROOT)
        return MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(extension)
            ?: DEFAULT_MIME_TYPE
    }

    private fun buildClipData(
        file: File,
        contentUri: Uri,
        mimeType: String,
    ): ClipData {
        return ClipData(
            file.name,
            arrayOf(mimeType),
            ClipData.Item(contentUri),
        )
    }

    private val fileProviderAuthority: String
        get() = "${context.packageName}.providers.fileprovider"

    private companion object {
        private const val DEFAULT_MIME_TYPE = "application/octet-stream"
        private const val DRAG_FLAGS =
            View.DRAG_FLAG_GLOBAL or View.DRAG_FLAG_GLOBAL_URI_READ
    }
}
