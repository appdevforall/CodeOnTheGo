package com.itsaky.androidide.utils

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.File

object UriFileImporter {
    const val TAG = "UriFileImporter"

    @JvmStatic
    fun copyUriToFile(
        context: Context,
        uri: Uri,
        destinationFile: File,
        onOpenFailed: (() -> Throwable)? = null,
    ) {
        copyUriToFile(
            contentResolver = context.contentResolver,
            uri = uri,
            destinationFile = destinationFile,
            onOpenFailed = onOpenFailed,
        )
    }

    @JvmStatic
    fun copyUriToFile(
        contentResolver: ContentResolver,
        uri: Uri,
        destinationFile: File,
        onOpenFailed: (() -> Throwable)? = null,
    ) {
        val inputStream = contentResolver.openInputStream(uri)
            ?: throw (onOpenFailed?.invoke() ?: IllegalStateException("Unable to open URI: $uri"))

        try {
            inputStream.use { input ->
                destinationFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            if (destinationFile.exists()) {
                destinationFile.delete()
            }
            throw e
        }
    }

    @JvmStatic
    fun getDisplayName(contentResolver: ContentResolver, uri: Uri): String? {
        return try {
            when (uri.scheme) {
                "content" -> queryDisplayName(contentResolver, uri) ?: uri.lastPathSegment
                "file" -> uri.lastPathSegment
                else -> uri.lastPathSegment
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting filename from URI", e)
            uri.lastPathSegment
        }
    }

    private fun queryDisplayName(contentResolver: ContentResolver, uri: Uri): String? {
        return contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1 && cursor.moveToFirst()) {
                cursor.getString(nameIndex)
            } else {
                null
            }
        }
    }
}
