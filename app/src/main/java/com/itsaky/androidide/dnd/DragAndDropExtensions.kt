package com.itsaky.androidide.dnd

import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.net.Uri
import android.view.DragEvent
import androidx.core.net.toUri

/**
 * Checks if the [DragEvent] contains any URIs that can be imported into the project.
 */
fun DragEvent.hasImportableContent(context: Context): Boolean {
    if (localState != null) return false

    return when (action) {
        DragEvent.ACTION_DROP -> {
            val clip = clipData ?: return false
            (0 until clip.itemCount).any { index ->
                clip.getItemAt(index).toImportableExternalUri(context) != null
            }
        }
        else -> clipDescription?.hasImportableMimeType() == true
    }
}

/**
 * Resolves the [ClipData.Item] to an external [Uri], ignoring internal application URIs.
 */
fun ClipData.Item.toImportableExternalUri(context: Context): Uri? {
    val resolvedUri = toExternalUri() ?: return null
    return resolvedUri.takeUnless { it.isInternalDragUri(context) }
}

private fun Uri.isInternalDragUri(context: Context): Boolean {
    return authority == "${context.packageName}.providers.fileprovider"
}

private fun ClipData.Item.toExternalUri(): Uri? {
    return uri
        ?: text?.toString()
            ?.takeIf { it.startsWith("content://") || it.startsWith("file://") }
            ?.toUri()
}

private fun ClipDescription.hasImportableMimeType(): Boolean {
    return hasMimeType(ClipDescription.MIMETYPE_TEXT_URILIST) ||
           hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) ||
           hasMimeType("*/*")
}
