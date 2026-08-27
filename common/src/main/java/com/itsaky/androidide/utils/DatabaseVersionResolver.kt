package com.itsaky.androidide.utils

import android.database.sqlite.SQLiteDatabase
import org.slf4j.LoggerFactory

object DatabaseVersionResolver {
	const val VERSION_UNKNOWN = "Version Unknown"

	private val log = LoggerFactory.getLogger(DatabaseVersionResolver::class.java)

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

	// One row by contract (ADFA-5220); the ORDER BY is the defence for a file that breaks it, and
	// MAX(major) is the tempting wrong answer -- a rebuild from an older content set has to read as
	// the downgrade it is. The count rides along so the breach can be reported rather than papered
	// over.
	private const val QUERY_MAJOR_VERSION = """
		SELECT major, (SELECT COUNT(*) FROM DocumentationDatabaseVersion)
		FROM   DocumentationDatabaseVersion
		ORDER BY changeTime DESC, rowid DESC
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
					log.error("Missing 'wholedb' record in LastChange table; falling back to {}", result)
					return result
				}
			}

			log.error("No versioning information available")
			VERSION_UNKNOWN
		} catch (e: Exception) {
			log.error("No versioning information available", e)
			VERSION_UNKNOWN
		}
	}

	/**
	 * The MAJOR version [db] declares in `DocumentationDatabaseVersion` (ADFA-5220), or null when
	 * that table is absent, empty, or holds a NULL major -- the first of which is how every database
	 * built before it existed identifies itself.
	 *
	 * The table is contractually a single row. A file carrying several is accepted rather than
	 * rejected -- the row with the highest `rowid` wins, so the answer stays deterministic and a
	 * downgrade still reads as one -- and logs a warning, since this reader cannot repair the file
	 * and refusing to serve documentation over it would be a worse outcome than serving it.
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
			if (!cursor.moveToFirst()) {
				return@use null
			}
			// Counted before the NULL check, not after: a file that is both multi-row *and* ends in a
			// NULL major would otherwise return null with nothing logged -- the most malformed case
			// there is, reported as if the table simply did not exist.
			val rows = cursor.getInt(1)
			if (rows > 1) {
				log.warn(
					"DocumentationDatabaseVersion holds {} rows; it is meant to hold one. Using the row written " +
						"last; the database was built by something that appended instead of replacing.",
					rows,
				)
			}
			if (cursor.isNull(0)) {
				// Logged, because the caller cannot tell this apart from the answer it gets for a
				// database predating the table: both are null, and WebServer reports "version none" and
				// skips the dictionary either way. For a real pre-ADFA-5220 file that is correct; for
				// this one it silently disables dictionary decoding on content that needs it, which is
				// the worse of the two contract breaches this reader defends against.
				log.warn(
					"DocumentationDatabaseVersion's newest row has a NULL major; treating the database as " +
						"declaring no version, which disables dictionary decoding.",
				)
				null
			} else {
				cursor.getInt(0)
			}
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
		// ifEmpty: a row whose changeTime, set and who are all null or blank produced "", which callers
		// then stored and logged as a stamp ("Database last change: ."). Nothing usable is the same
		// answer as no row at all.
		return parts.joinToString(separator = " ").ifEmpty { VERSION_UNKNOWN }
	}
}
