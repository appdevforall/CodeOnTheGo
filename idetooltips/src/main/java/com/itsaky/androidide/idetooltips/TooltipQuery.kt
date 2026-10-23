package com.itsaky.androidide.idetooltips

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.annotation.VisibleForTesting

// Looks the category up first, so the tag is found through Tooltips' UNIQUE (categoryId, tag)
// index. Written as a join, it made SQLite's planner scan all of Tooltips (46,128 rows, 13 MB)
// for every tooltip shown, because documentation.db carries no ANALYZE statistics (ADFA-6597).
// IN rather than =, so two categories sharing a name still come back as duplicates, as before.
@VisibleForTesting
internal const val QUERY_TOOLTIP = """
	SELECT T.rowid, T.id, T.summary, T.detail
	FROM Tooltips AS T
	WHERE T.categoryId IN (SELECT id FROM TooltipCategories WHERE category = ?)
	AND T.tag = ?
"""

// Outside TooltipManager, so tests can run it without initializing the object, which reads the
// installed app's Environment. Binds the arguments in the order QUERY_TOOLTIP expects them.
internal fun queryTooltip(
	database: SQLiteDatabase,
	category: String,
	tag: String,
): Cursor = database.rawQuery(QUERY_TOOLTIP, arrayOf(category, tag))
