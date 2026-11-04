package com.itsaky.androidide.plugins.manager.documentation

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.itsaky.androidide.utils.DatabaseVersionResolver

/** One plugin tooltip as it is written: summary and detail final, buttons in display order. */
internal class PluginTooltipRow(
	val tag: String,
	val summary: String,
	val detail: String,
	val buttons: List<PluginTooltipButtonRow>,
)

/** One button of a [PluginTooltipRow], its uri already resolved. */
internal class PluginTooltipButtonRow(
	val description: String,
	val uri: String,
)

// A subquery rather than a join, so Tooltips is searched by its (categoryId, tag) key.
private const val CATEGORY_IDS = "SELECT id FROM TooltipCategories WHERE category = ?"
private const val CATEGORY_TOOLTIP_IDS = "SELECT id FROM Tooltips WHERE categoryId IN ($CATEGORY_IDS)"

// IN takes one variable per string; 500 stays under SQLite's pre-3.32 default limit of 999.
private const val LOOKUP_CHUNK = 500

/**
 * Replaces every tooltip in [category] with [tooltips], inside the caller's transaction.
 *
 * Writes documentation version 3's tooltip tables (ADFA-3305): the key in Tooltips, the text in
 * TooltipTexts, and each button's text as ids into TooltipButtonDescriptions and TooltipButtonUris.
 * Refuses an older database before writing anything. Every insert throws on failure, so a failed
 * one rolls the transaction back rather than commit a tooltip without its buttons.
 */
internal fun writePluginTooltips(
	db: SQLiteDatabase,
	category: String,
	tooltips: List<PluginTooltipRow>,
) {
	DatabaseVersionResolver.requireKeyedTooltips(db)
	deleteCategoryTooltips(db, category)

	val categoryId = insertOrGetCategoryId(db, category)
	val buttons = tooltips.flatMap { it.buttons }
	val descriptionIds = textIds(db, "TooltipButtonDescriptions", "description", buttons.map { it.description })
	val uriIds = textIds(db, "TooltipButtonUris", "uri", buttons.map { it.uri })

	for (tooltip in tooltips) {
		val tooltipId = insertTooltip(db, categoryId, tooltip)
		tooltip.buttons.forEachIndexed { index, button ->
			val values =
				ContentValues().apply {
					put("tooltipId", tooltipId)
					put("buttonNumberId", index)
					put("descriptionId", descriptionIds.getValue(button.description))
					put("uriId", uriIds.getValue(button.uri))
				}
			db.insertOrThrow("TooltipButtons", null, values)
		}
	}
	deleteUnusedButtonText(db)
}

/**
 * Removes [category] and every tooltip in it, inside the caller's transaction, with the button
 * text no other button uses. Refuses a database older than documentation version 3.
 */
internal fun removePluginTooltips(
	db: SQLiteDatabase,
	category: String,
) {
	DatabaseVersionResolver.requireKeyedTooltips(db)
	deleteCategoryTooltips(db, category)
	deleteUnusedButtonText(db)
}

private fun deleteCategoryTooltips(
	db: SQLiteDatabase,
	category: String,
) {
	// Buttons and text first: both are found through the Tooltips rows deleted after them.
	db.execSQL("DELETE FROM TooltipButtons WHERE tooltipId IN ($CATEGORY_TOOLTIP_IDS)", arrayOf(category))
	db.execSQL("DELETE FROM TooltipTexts WHERE id IN ($CATEGORY_TOOLTIP_IDS)", arrayOf(category))
	db.execSQL("DELETE FROM Tooltips WHERE categoryId IN ($CATEGORY_IDS)", arrayOf(category))
	db.delete("TooltipCategories", "category = ?", arrayOf(category))
}

// A description or uri no button names any more: one only a removed tooltip used. Text shared with
// a built-in button, or another plugin's, stays.
private fun deleteUnusedButtonText(db: SQLiteDatabase) {
	db.execSQL("DELETE FROM TooltipButtonDescriptions WHERE id NOT IN (SELECT descriptionId FROM TooltipButtons)")
	db.execSQL("DELETE FROM TooltipButtonUris WHERE id NOT IN (SELECT uriId FROM TooltipButtons)")
}

/**
 * The id of each of [values] in [table], inserting the ones it lacks. [column] has no index -- a
 * UNIQUE one would cost about as much as the table -- so the strings are looked up in chunks, each
 * one read of the table, rather than one read per string.
 */
private fun textIds(
	db: SQLiteDatabase,
	table: String,
	column: String,
	values: List<String>,
): Map<String, Long> {
	val distinct = values.distinct()
	val ids = HashMap<String, Long>()
	for (chunk in distinct.chunked(LOOKUP_CHUNK)) {
		val placeholders = chunk.joinToString(",") { "?" }
		db.rawQuery("SELECT $column, id FROM $table WHERE $column IN ($placeholders)", chunk.toTypedArray()).use { c ->
			while (c.moveToNext()) ids.putIfAbsent(c.getString(0), c.getLong(1))
		}
	}
	for (value in distinct) {
		if (value !in ids) {
			ids[value] = db.insertOrThrow(table, null, ContentValues().apply { put(column, value) })
		}
	}
	return ids
}

private fun insertOrGetCategoryId(
	db: SQLiteDatabase,
	category: String,
): Long {
	val cursor =
		db.query(
			"TooltipCategories",
			arrayOf("id"),
			"category = ?",
			arrayOf(category),
			null,
			null,
			null,
		)

	if (cursor.moveToFirst()) {
		val id = cursor.getLong(0)
		cursor.close()
		return id
	}
	cursor.close()

	val values =
		ContentValues().apply {
			put("category", category)
		}
	return db.insertOrThrow("TooltipCategories", null, values)
}

private fun insertTooltip(
	db: SQLiteDatabase,
	categoryId: Long,
	tooltip: PluginTooltipRow,
): Long {
	val existingId =
		db
			.rawQuery(
				"SELECT id FROM Tooltips WHERE categoryId = ? AND tag = ?",
				arrayOf(categoryId.toString(), tooltip.tag),
			).use { if (it.moveToFirst()) it.getLong(0) else null }

	val text =
		ContentValues().apply {
			put("summary", tooltip.summary)
			put("detail", tooltip.detail)
		}

	if (existingId != null) {
		// Only a tag the plugin lists twice, since the category was just emptied; the later wins.
		db.update("TooltipTexts", text, "id = ?", arrayOf(existingId.toString()))
		db.delete("TooltipButtons", "tooltipId = ?", arrayOf(existingId.toString()))
		return existingId
	}

	// TooltipTexts hands out the id: AUTOINCREMENT, so never one a deleted tooltip had. Tooltips is
	// WITHOUT ROWID, and SQLite does not record its inserts as a rowid, so what insertOrThrow
	// returns for it is some earlier insert's.
	val id = db.insertOrThrow("TooltipTexts", null, text)
	val key =
		ContentValues().apply {
			put("id", id)
			put("categoryId", categoryId)
			put("tag", tooltip.tag)
		}
	db.insertOrThrow("Tooltips", null, key)
	return id
}
