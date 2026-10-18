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

/** The (description, uri) of each of [tooltipId]'s buttons, in display order. */
internal fun queryTooltipButtons(
	database: SQLiteDatabase,
	tooltipId: Int,
): List<Pair<String, String>> =
	database.rawQuery(QUERY_TOOLTIP_BUTTONS, arrayOf(tooltipId.toString())).use { c ->
		buildList { while (c.moveToNext()) add(Pair(c.getString(0), c.getString(1))) }
	}
