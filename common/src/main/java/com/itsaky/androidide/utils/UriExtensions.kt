package com.itsaky.androidide.utils

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns

fun Uri.getFileName(context: Context): String {
    var result: String? = null
    if (this.scheme == "content") {
        val cursor: Cursor? = context.contentResolver.query(this, null, null, null, null)
        try {
            if (cursor != null && cursor.moveToFirst()) {
                val colIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (colIndex >= 0) {
                    result = cursor.getString(colIndex)
                }
            }
        } finally {
            cursor?.close()
        }
    }
    if (result == null) {
        result = this.path
        val cut = result?.lastIndexOf('/')
        if (cut != null && cut != -1) {
            result = result.substring(cut + 1)
        }
    }
    return result ?: "Unknown File"
}