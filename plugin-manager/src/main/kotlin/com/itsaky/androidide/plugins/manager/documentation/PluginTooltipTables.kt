package com.itsaky.androidide.plugins.manager.documentation

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

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

/**
 * Replaces every tooltip in [category] with [tooltips], inside the caller's transaction.
 */
internal fun writePluginTooltips(
	db: SQLiteDatabase,
	category: String,
	tooltips: List<PluginTooltipRow>,
) {
	removePluginTooltips(db, category)

	val categoryId = insertOrGetCategoryId(db, category)

	for (tooltip in tooltips) {
		val tooltipId = insertTooltip(db, categoryId, tooltip)
		tooltip.buttons.forEachIndexed { index, button ->
			insertTooltipButton(db, tooltipId, button.description, button.uri, index)
		}
	}
}

/**
 * Removes [category] and every tooltip in it, inside the caller's transaction.
 */
internal fun removePluginTooltips(
	db: SQLiteDatabase,
	category: String,
) {
	val cursor =
		db.rawQuery(
			"""
			SELECT T.id FROM Tooltips AS T
			INNER JOIN TooltipCategories AS TC ON T.categoryId = TC.id
			WHERE TC.category = ?
			""".trimIndent(),
			arrayOf(category),
		)

	val tooltipIds = mutableListOf<Long>()
	while (cursor.moveToNext()) {
		tooltipIds.add(cursor.getLong(0))
	}
	cursor.close()

	if (tooltipIds.isNotEmpty()) {
		val placeholders = tooltipIds.joinToString(",") { "?" }
		val args = tooltipIds.map { it.toString() }.toTypedArray()
		db.delete("TooltipButtons", "tooltipId IN ($placeholders)", args)
		db.delete("Tooltips", "id IN ($placeholders)", args)
	}

	db.delete("TooltipCategories", "category = ?", arrayOf(category))
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
	return db.insert("TooltipCategories", null, values)
}

private fun insertTooltip(
	db: SQLiteDatabase,
	categoryId: Long,
	tooltip: PluginTooltipRow,
): Long {
	val existingCursor =
		db.query(
			"Tooltips",
			arrayOf("id"),
			"categoryId = ? AND tag = ?",
			arrayOf(categoryId.toString(), tooltip.tag),
			null,
			null,
			null,
		)

	if (existingCursor.moveToFirst()) {
		val existingId = existingCursor.getLong(0)
		existingCursor.close()

		val updateValues =
			ContentValues().apply {
				put("summary", tooltip.summary)
				put("detail", tooltip.detail)
			}
		db.update("Tooltips", updateValues, "id = ?", arrayOf(existingId.toString()))
		db.delete("TooltipButtons", "tooltipId = ?", arrayOf(existingId.toString()))
		return existingId
	}
	existingCursor.close()

	val values =
		ContentValues().apply {
			put("categoryId", categoryId)
			put("tag", tooltip.tag)
			put("summary", tooltip.summary)
			put("detail", tooltip.detail)
		}
	return db.insert("Tooltips", null, values)
}

private fun insertTooltipButton(
	db: SQLiteDatabase,
	tooltipId: Long,
	description: String,
	uri: String,
	order: Int,
) {
	val values =
		ContentValues().apply {
			put("tooltipId", tooltipId)
			put("description", description)
			put("uri", uri)
			put("buttonNumberId", order)
		}
	db.insert("TooltipButtons", null, values)
}
