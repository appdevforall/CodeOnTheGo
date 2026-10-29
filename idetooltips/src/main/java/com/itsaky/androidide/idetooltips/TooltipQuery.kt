package com.itsaky.androidide.idetooltips

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.annotation.VisibleForTesting
import com.itsaky.androidide.utils.DatabaseVersionResolver

// From documentation version 3 on (ADFA-3305), Tooltips is keyed by (categoryId, tag) and holds
// only the id; the text is in TooltipTexts, one row per id. Looks the category up first, so the
// tag is found by that key. Written as a join, it made SQLite's planner scan all of Tooltips for
// every tooltip shown, because documentation.db carries no ANALYZE statistics (ADFA-6597).
// IN rather than =, so two categories sharing a name still come back as duplicates, as before.
@VisibleForTesting
internal const val QUERY_TOOLTIP = """
	SELECT T.id, X.summary, X.detail
	FROM Tooltips AS T
	JOIN TooltipTexts AS X ON X.id = T.id
	WHERE T.categoryId IN (SELECT id FROM TooltipCategories WHERE category = ?)
	AND T.tag = ?
"""

// TooltipButtons is keyed by (tooltipId, buttonNumberId), so a tooltip's buttons are one range of
// that key, already in display order. Each distinct description and uri is stored once.
@VisibleForTesting
internal const val QUERY_TOOLTIP_BUTTONS = """
	SELECT D.description, U.uri
	FROM TooltipButtons AS B
	JOIN TooltipButtonDescriptions AS D ON D.id = B.descriptionId
	JOIN TooltipButtonUris AS U ON U.id = B.uriId
	WHERE B.tooltipId = ?
	ORDER BY B.buttonNumberId
"""

// Outside TooltipManager, so tests can run it without initializing the object, which reads the
// installed app's Environment. Binds the arguments in the order QUERY_TOOLTIP expects them.
// Refuses a database older than version 3 first, rather than fail on whichever old table or
// column the query meets.
internal fun queryTooltip(
	database: SQLiteDatabase,
	category: String,
	tag: String,
): Cursor {
	DatabaseVersionResolver.requireKeyedTooltips(database)
	return database.rawQuery(QUERY_TOOLTIP, arrayOf(category, tag))
}

/**
 * The tooltip for [category] and [tag] as TooltipManager shows it, its buttons pointed at the local
 * documentation server, stamped with [lastChange]. Throws [NoTooltipFoundException] when the
 * database has none and [DatabaseCorruptionException] when it has more than one.
 */
internal fun readTooltip(
	database: SQLiteDatabase,
	category: String,
	tag: String,
	lastChange: String,
): IDETooltipItem =
	queryTooltip(database, category = category, tag = tag).use { c ->
		when (c.count) {
			// A missing tag is a normal outcome (e.g. a Gradle task with no
			// authored tooltip). Honor the nullable contract so the caller
			// shows nothing rather than an "n/a" popup.
			0 -> {
				throw NoTooltipFoundException(category, tag)
			}

			1 -> { // Expected case, continue processing
			}

			else -> {
				throw DatabaseCorruptionException(
					"Multiple tooltips found for category='$category', tag='$tag' (found ${c.count} rows). " +
						"Each category/tag combination should be unique.",
				)
			}
		}

		c.moveToFirst()
		val tooltipId = c.getInt(0)
		val summary = c.getString(1)
		val detail = c.getString(2)

		val buttons = ArrayList<Pair<String, String>>()
		for ((description, uri) in queryTooltipButtons(database, tooltipId)) {
			buttons.add(Pair(description, "http://localhost:6174/$uri"))
		}

		// Tooltips has no rowid from documentation version 3 on. It always equalled
		// id before (id was the INTEGER PRIMARY KEY), so the debug dialog's Row is
		// still the id.
		IDETooltipItem(tooltipId, tooltipId, category, tag, summary, detail, buttons, lastChange)
	}

/** The (description, uri) of each of [tooltipId]'s buttons, in display order. */
internal fun queryTooltipButtons(
	database: SQLiteDatabase,
	tooltipId: Int,
): List<Pair<String, String>> =
	database.rawQuery(QUERY_TOOLTIP_BUTTONS, arrayOf(tooltipId.toString())).use { c ->
		buildList { while (c.moveToNext()) add(Pair(c.getString(0), c.getString(1))) }
	}
