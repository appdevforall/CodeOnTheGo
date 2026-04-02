package com.itsaky.androidide.dnd

import android.content.ClipData
import android.content.ClipDescription
import android.content.ContentResolver
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
                clip.getItemAt(index).toImportableExternalUris(context).isNotEmpty()
            }
        }

        else -> clipDescription?.hasImportableMimeType() == true
    }
}

/**
 * Resolves the [ClipData.Item] to a list of external [Uri]s, ignoring internal application URIs.
 */
fun ClipData.Item.toImportableExternalUris(context: Context): List<Uri> {
    return toExternalUris().filterNot { it.isInternalDragUri(context) }
}

private fun Uri.isInternalDragUri(context: Context): Boolean {
    return authority == "${context.packageName}.providers.fileprovider"
}

private fun ClipData.Item.toExternalUris(): List<Uri> {
    uri?.let { return listOf(it) }

    val textContent = text?.toString() ?: return emptyList()

    return textContent.lineSequence()
        .map { it.trim() }
        .map { it.toUri() }
        .filter { it.scheme == ContentResolver.SCHEME_CONTENT || it.scheme == ContentResolver.SCHEME_FILE }
        .toList()
}

private fun ClipDescription.hasImportableMimeType(): Boolean {
    return hasMimeType(ClipDescription.MIMETYPE_TEXT_URILIST) ||
        hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) ||
        hasMimeType("*/*")
}
