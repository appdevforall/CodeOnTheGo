package com.itsaky.androidide.plugins.manager.documentation

import android.database.sqlite.SQLiteDatabase
import android.webkit.MimeTypeMap

internal data class ContentTypeRow(
    val id: Long,
    val compression: String
)

internal class ExtensionToContentTypeResolver {

    private val cache = HashMap<String, ContentTypeRow?>()

    fun resolve(db: SQLiteDatabase, extension: String): ContentTypeRow? {
        val ext = extension.lowercase()
        cache[ext]?.let { return it }
        if (cache.containsKey(ext)) return null

        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: FALLBACK_MIME[ext]

        val row = mime?.let { lookup(db, it) }
        cache[ext] = row
        return row
    }

    private fun lookup(db: SQLiteDatabase, mime: String): ContentTypeRow? {
        db.rawQuery(
            "SELECT id, compression FROM ContentTypes WHERE value = ? LIMIT 1",
            arrayOf(mime)
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return ContentTypeRow(cursor.getLong(0), cursor.getString(1) ?: "none")
        }
    }

    companion object {
        private val FALLBACK_MIME = mapOf(
            "map"   to "application/json",
            "md"    to "text/markdown",
            "woff"  to "font/woff",
            "woff2" to "font/woff2",
            "ttf"   to "font/ttf",
            "otf"   to "font/otf",
            "mjs"   to "application/javascript",
            "wasm"  to "application/wasm"
        )
    }
}
