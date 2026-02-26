package com.itsaky.androidide.utils

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.net.URLDecoder

fun Uri.getFileName(context: Context): String {
    val unknownFile = "Unknown File"
    if (scheme == "content") {
        try {
            context.contentResolver.query(this, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) {
                        return cursor.getString(nameIndex) ?: unknownFile
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.w("UriExtensions", "SecurityException while reading URI: ${scheme}://${authority}", e)
        } catch (e: Exception) {
            Log.w("UriExtensions", "Unexpected error while reading URI: ${scheme}://${authority}", e)
        }

        return unknownFile
    }

    val fallbackName = path?.substringAfterLast('/') ?: unknownFile
    return try {
        URLDecoder.decode(fallbackName, "UTF-8")
    } catch (_: Exception) { fallbackName }
}