package com.itsaky.androidide.utils

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("UriExtensions")

fun Uri.getFileName(context: Context): String = getFileName(context.contentResolver)

fun Uri.getFileName(contentResolver: ContentResolver): String {
	val unknownFileLabel = "Unknown File"
	if (scheme == "content") {
		try {
			contentResolver.query(this, null, null, null, null)?.use { cursor ->
				if (cursor.moveToFirst()) {
					val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
					if (nameIndex >= 0) {
						return cursor.getString(nameIndex) ?: unknownFileLabel
					}
				}
			}
		} catch (e: Exception) {
			// Broad on purpose: a third-party content provider can throw almost anything
			// (SecurityException, unresolvable-URI IllegalArgumentException,
			// CursorWindowAllocationException, a RuntimeException wrapping a dead Binder, ...)
			// and this is a best-effort display-name lookup, not a critical path.
			log.warn("Failed to read display name for URI: {}://{}", scheme, authority, e)
		}

		return unknownFileLabel
	}

	val fallbackName = path?.substringAfterLast('/') ?: unknownFileLabel
	val decodedName = Uri.decode(fallbackName)
	return decodedName.ifBlank { unknownFileLabel }
}
