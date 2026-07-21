package org.appdevforall.cotg.corpus.recvprov

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

/**
 * In-memory notes provider. onCreate seeds the store; the manifest declaration
 * makes Android instantiate this class at process start, ahead of any Activity
 * - the early-init path the proxy factory must serve from the current
 * generation, not gen-0.
 */
class NotesProvider : ContentProvider() {
	private val notes = mutableListOf<String>()

	override fun onCreate(): Boolean {
		notes.add("seed note (provider onCreate ran)")
		return true
	}

	override fun query(
		uri: Uri,
		projection: Array<String>?,
		selection: String?,
		selectionArgs: Array<String>?,
		sortOrder: String?,
	): Cursor {
		val cursor = MatrixCursor(arrayOf(COL_ID, COL_LABEL))
		notes.forEachIndexed { index, label -> cursor.addRow(arrayOf<Any>(index, label)) }
		return cursor
	}

	override fun insert(uri: Uri, values: ContentValues?): Uri? {
		val label = values?.getAsString(COL_LABEL) ?: return null
		notes.add(label)
		return Uri.withAppendedPath(CONTENT_URI, (notes.size - 1).toString())
	}

	override fun update(
		uri: Uri,
		values: ContentValues?,
		selection: String?,
		selectionArgs: Array<String>?,
	): Int = 0

	override fun delete(
		uri: Uri,
		selection: String?,
		selectionArgs: Array<String>?,
	): Int = 0

	override fun getType(uri: Uri): String = "vnd.android.cursor.dir/vnd.$AUTHORITY.note"

	companion object {
		const val AUTHORITY = "org.appdevforall.cotg.corpus.recvprov.notes"
		const val COL_ID = "_id"
		const val COL_LABEL = "label"
		val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY/notes")
	}
}
