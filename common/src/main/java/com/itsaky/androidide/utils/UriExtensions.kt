package com.itsaky.androidide.utils

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log

fun Uri.getFileName(context: Context): String {
    val unknownFileLabel = "Unknown File"
    if (scheme == "content") {
        try {
            context.contentResolver.query(this, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) {
                        return cursor.getString(nameIndex) ?: unknownFileLabel
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.w("UriExtensions", "SecurityException while reading URI: ${scheme}://${authority}", e)
        } catch (e: Exception) {
            Log.w("UriExtensions", "Unexpected error while reading URI: ${scheme}://${authority}", e)
        }

        return unknownFileLabel
    }

    val fallbackName = path?.substringAfterLast('/') ?: unknownFileLabel
    val decodedName = Uri.decode(fallbackName)
    return decodedName.ifBlank { unknownFileLabel }
}