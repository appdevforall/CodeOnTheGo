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
    data class NotStarted(val reason: FileDragFailureReason) : FileDragResult
    data class Failed(val throwable: Throwable? = null) : FileDragResult
}

enum class FileDragFailureReason {
    FILE_NOT_FOUND,
    NOT_A_FILE,
    DRAG_NOT_STARTED,
}

class FileDragStarter(
    private val context: Context,
) {

    fun startDrag(sourceView: View, file: File): FileDragResult {
        if (!file.exists()) {
            return FileDragResult.NotStarted(FileDragFailureReason.FILE_NOT_FOUND)
        }

        if (!file.isFile) {
            return FileDragResult.NotStarted(FileDragFailureReason.NOT_A_FILE)
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
            onSuccess = ::toDragResult,
            onFailure = ::toFailureResult,
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

    private fun toDragResult(started: Boolean): FileDragResult {
        if (started) {
            return FileDragResult.Started
        }

        return FileDragResult.NotStarted(FileDragFailureReason.DRAG_NOT_STARTED)
    }

    private fun toFailureResult(throwable: Throwable): FileDragResult {
        return FileDragResult.Failed(throwable)
    }

    private val fileProviderAuthority: String
        get() = "${context.packageName}.providers.fileprovider"

    private companion object {
        private const val DEFAULT_MIME_TYPE = "application/octet-stream"
        private const val DRAG_FLAGS =
            View.DRAG_FLAG_GLOBAL or View.DRAG_FLAG_GLOBAL_URI_READ
    }
}
