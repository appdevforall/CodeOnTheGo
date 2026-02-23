package com.itsaky.androidide.utils

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.net.URLDecoder

fun Uri.getFileName(context: Context): String {
    if (scheme == "content") {
        try {
            context.contentResolver.query(this, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) {
                        return cursor.getString(nameIndex)
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.w("UriExtensions", "SecurityException while reading URI: $this", e)
        } catch (e: Exception) {
            Log.w("UriExtensions", "Unexpected error while reading URI: $this", e)
        }

        return "Unknown File"
    }

    val fallbackName = path?.substringAfterLast('/') ?: "Unknown File"
    return try {
        URLDecoder.decode(fallbackName, "UTF-8")
    } catch (_: Exception) { fallbackName }
}