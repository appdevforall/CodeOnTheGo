package com.itsaky.androidide.utils

import android.database.sqlite.SQLiteDatabase
import android.util.Log

object DatabaseVersionResolver {
	const val VERSION_UNKNOWN = "Version Unknown"

	private const val TAG = "DatabaseVersionResolver"

	private const val QUERY_WHOLEDB = """
		SELECT changeTime, who
		FROM   LastChange
		WHERE  documentationSet = 'wholedb'
		LIMIT  1
	"""

	// ADFA-5220's DocumentationDatabaseVersion table. A database declaring at least this MAJOR
	// version has its brotli `Content` rows compressed against `CompressionDictionary` (ADFA-5153);
	// one declaring less -- or carrying no version table at all -- predates that migration, and its
	// rows are plain Brotli.
	const val MAJOR_VERSION_WITH_COMPRESSION_DICTIONARY = 2

	private const val QUERY_VERSION_TABLE_EXISTS = """
		SELECT 1
		FROM   sqlite_master
		WHERE  type = 'table' AND name = 'DocumentationDatabaseVersion'
	"""

	// The table holds exactly one row: the version the database *is*, not a history of what it has
	// been (ADFA-5220), and the pipeline replaces that row rather than appending. So the ORDER BY
	// here is a defence, not a model -- if a database ever turns up carrying several rows, this
	// reads the one written last instead of whichever SQLite happens to return, and a downgrade
	// still reads as a downgrade where MAX(major) would report the highest version ever recorded.
	private const val QUERY_MAJOR_VERSION = """
		SELECT major
		FROM   DocumentationDatabaseVersion
		ORDER BY rowid DESC
		LIMIT  1
	"""

	private const val QUERY_FALLBACK_LATEST = """
		SELECT changeTime, documentationSet, who
		FROM   LastChange
		ORDER BY changeTime DESC
		LIMIT  1
	"""

	fun resolveDatabaseVersion(db: SQLiteDatabase): String {
		return try {
			db.rawQuery(QUERY_WHOLEDB, arrayOf()).use { c ->
				if (c.moveToFirst()) {
					return formatVersion(
						changeTime = c.getString(0),
						who = c.getString(1),
					)
				}
			}

			db.rawQuery(QUERY_FALLBACK_LATEST, arrayOf()).use { c ->
				if (c.moveToFirst()) {
					val result =
						formatVersion(
							changeTime = c.getString(0),
							who = c.getString(2),
							documentationSet = c.getString(1),
						)
					Log.e(
						TAG,
						"Missing 'wholedb' record in LastChange table; falling back to $result",
					)
					return result
				}
			}

			Log.e(TAG, "No versioning information available")
			VERSION_UNKNOWN
		} catch (e: Exception) {
			Log.e(TAG, "No versioning information available", e)
			VERSION_UNKNOWN
		}
	}

	/**
	 * The MAJOR version [db] declares in `DocumentationDatabaseVersion` (ADFA-5220), or null when
	 * that table is absent or empty -- which is how every database built before it existed
	 * identifies itself.
	 *
	 * Deliberately does *not* catch exceptions, unlike [resolveDatabaseVersion]: callers cache the
	 * answer for the lifetime of a database (see `WebServer.loadCompressionDictionary`), so a
	 * transient `SQLiteException` has to stay distinguishable from a definitive "no version table",
	 * or one hiccup would pin the database at unversioned until it is swapped.
	 */
	fun resolveMajorVersion(db: SQLiteDatabase): Int? {
		val tableExists = db.rawQuery(QUERY_VERSION_TABLE_EXISTS, arrayOf()).use { it.moveToFirst() }
		if (!tableExists) {
			return null
		}
		return db.rawQuery(QUERY_MAJOR_VERSION, arrayOf()).use { cursor ->
			if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getInt(0) else null
		}
	}

	private fun formatVersion(
		changeTime: String?,
		who: String?,
		documentationSet: String? = null,
	): String {
		val parts = mutableListOf<String>()
		if (!changeTime.isNullOrBlank()) parts += changeTime
		if (!documentationSet.isNullOrBlank()) parts += "($documentationSet)"
		if (!who.isNullOrBlank()) parts += who
		return parts.joinToString(separator = " ")
	}
}
