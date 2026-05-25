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
					val result = formatVersion(
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
