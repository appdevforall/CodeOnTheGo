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
		} catch (e: SecurityException) {
			log.warn("Denied access while reading URI: {}://{}", scheme, authority, e)
		} catch (e: IllegalArgumentException) {
			// No registered provider for this URI, or the provider rejected the query args.
			log.warn("No provider could resolve URI: {}://{}", scheme, authority, e)
		}

		return unknownFileLabel
	}

	val fallbackName = path?.substringAfterLast('/') ?: unknownFileLabel
	val decodedName = Uri.decode(fallbackName)
	return decodedName.ifBlank { unknownFileLabel }
}
