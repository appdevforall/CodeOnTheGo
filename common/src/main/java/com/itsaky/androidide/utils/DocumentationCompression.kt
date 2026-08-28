package com.itsaky.androidide.utils

import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.nio.ByteBuffer

private const val TAG = "DocumentationCompression"

/**
 * Copies [bytes] into a direct [ByteBuffer] -- brotli4j's `attachDictionary` requires a direct
 * buffer, a heap-backed one throws `IllegalArgumentException`.
 *
 * The capacity must be exactly [bytes]`.size`: `attachDictionary` reads the whole capacity and
 * ignores position/limit, so trailing slack from an over-allocated buffer is treated as dictionary
 * content and every decode then fails with `IOException: corrupted input`.
 */
fun toDirectByteBuffer(bytes: ByteArray): ByteBuffer =
	ByteBuffer.allocateDirect(bytes.size).apply {
		put(bytes)
		flip()
	}

/**
 * Loads the shared Brotli dictionary every `compression = 'brotli'` Content row in [db] is
 * compressed against (see ADFA-5153). Returns null (logged) when the database *definitively* has
 * no dictionary, which tells callers to read and write plain, dictionary-free brotli instead.
 *
 * Both the reader and the writer of `Content` call this, so the two cannot disagree about whether
 * a given database's brotli rows carry a dictionary -- which is the whole point: a row compressed
 * against a dictionary the reader does not attach is undecodable, and vice versa.
 *
 * The gate is the MAJOR version the database declares in ADFA-5220's version table, not the
 * presence of a `CompressionDictionary` table: table sniffing infers a whole content format from
 * one table's existence, and gets it wrong in both directions -- a database carrying the table but
 * *unmigrated* content would have every plain row read as dictionary-compressed. Below
 * [DatabaseVersionResolver.MAJOR_VERSION_WITH_COMPRESSION_DICTIONARY] the dictionary is neither
 * read nor attached.
 *
 * The `CompressionDictionary` checks below still run, for a database that declares a new-enough
 * version but has no usable dictionary row: without them the data query would raise "no such
 * table", which callers correctly read as transient and would then retry forever.
 *
 * Deliberately does *not* catch exceptions itself: an unexpected `SQLiteException`/IO failure is
 * likely transient, and callers must be able to tell that apart from a definitive absence. Caching
 * a transient failure as "no dictionary" would permanently disable dictionary handling for the
 * rest of this database's lifetime; writing plain rows because of one would corrupt content the
 * reader can never decode.
 */
fun loadCompressionDictionary(db: SQLiteDatabase): ByteBuffer? {
	val majorVersion = DatabaseVersionResolver.resolveMajorVersion(db)
	if (majorVersion == null || majorVersion < DatabaseVersionResolver.MAJOR_VERSION_WITH_COMPRESSION_DICTIONARY) {
		Log.w(
			TAG,
			"Database declares documentation version ${majorVersion ?: "none"}, below " +
				"${DatabaseVersionResolver.MAJOR_VERSION_WITH_COMPRESSION_DICTIONARY}; " +
				"brotli content is handled without a dictionary.",
		)
		return null
	}

	val tableExists =
		db
			.rawQuery(
				"SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'CompressionDictionary'",
				null,
			).use { it.moveToFirst() }
	if (!tableExists) {
		Log.w(TAG, "CompressionDictionary table not found; brotli content is handled without a dictionary.")
		return null
	}

	return db.rawQuery("SELECT data FROM CompressionDictionary WHERE id = 1", null).use { cursor ->
		if (!cursor.moveToFirst()) {
			Log.w(TAG, "CompressionDictionary table is empty; brotli content is handled without a dictionary.")
			return null
		}
		val bytes = cursor.getBlob(0)
		if (bytes == null) {
			Log.w(TAG, "CompressionDictionary row has a NULL data column; brotli content is handled without a dictionary.")
			return null
		}
		// An empty blob would yield a 0-capacity buffer, which attachDictionary rejects --
		// every row's dictionary decode would then fail with nothing above DEBUG to say why.
		if (bytes.isEmpty()) {
			Log.w(TAG, "CompressionDictionary row has an empty data column; brotli content is handled without a dictionary.")
			return null
		}
		toDirectByteBuffer(bytes)
	}
}
