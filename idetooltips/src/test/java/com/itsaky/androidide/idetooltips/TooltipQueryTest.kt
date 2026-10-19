package com.itsaky.androidide.idetooltips

import android.database.sqlite.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Runs [queryTooltip] and [queryTooltipButtons], the lookups TooltipManager.getTooltip makes, and
 * [readTooltip], which turns their rows into the item it shows,
 * against documentation.db's version 3 tooltip schema (ADFA-3305), as migrate_tooltip_schema.py in
 * OfflineDocumentationTools writes it. The query plans matter as much as the rows: the join
 * QUERY_TOOLTIP replaced scanned the whole 13 MB table for every tooltip shown (ADFA-6597), and the
 * buttons query used to scan and sort all of TooltipButtons.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class TooltipQueryTest {
	private lateinit var db: SQLiteDatabase

	@Before
	fun setUp() {
		db = SQLiteDatabase.create(null)
		for (statement in SCHEMA) db.execSQL(statement)
		setVersion(3)
		listOf("ide", "xml", "java", "kotlin").forEachIndexed { i, category ->
			db.execSQL("INSERT INTO TooltipCategories VALUES (?, ?)", arrayOf(i + 1, category))
		}
		db.execSQL("INSERT INTO TooltipButtonDescriptions VALUES (1, 'View full documentation'), (2, 'Examples')")
		db.beginTransaction()
		var id = 1000
		for (categoryId in 1..4) {
			for (n in 0 until 500) {
				id++
				db.execSQL("INSERT INTO TooltipTexts VALUES (?, ?, ?)", arrayOf(id, "summary $categoryId/$n", "detail"))
				db.execSQL("INSERT INTO Tooltips VALUES (?, ?, ?)", arrayOf(id, categoryId, "tag$n"))
				db.execSQL("INSERT INTO TooltipButtonUris VALUES (?, ?)", arrayOf(id, "page$id.html"))
				// Button 2 first, so the order has to come from the key, not from insertion.
				db.execSQL("INSERT INTO TooltipButtons VALUES (?, 2, 2, ?)", arrayOf(id, id))
				db.execSQL("INSERT INTO TooltipButtons VALUES (?, 1, 1, ?)", arrayOf(id, id))
			}
		}
		db.setTransactionSuccessful()
		db.endTransaction()
	}

	@After
	fun tearDown() {
		db.close()
	}

	private fun setVersion(major: Int) {
		db.execSQL("DELETE FROM DocumentationDatabaseVersion")
		db.execSQL(
			"INSERT INTO DocumentationDatabaseVersion (major, minor, patch, who, comment) VALUES (?, 0, 0, 'test', 'test')",
			arrayOf(major),
		)
	}

	private fun plan(
		query: String,
		vararg args: String,
	): List<String> =
		db.rawQuery("EXPLAIN QUERY PLAN $query", args).use { c ->
			buildList { while (c.moveToNext()) add(c.getString(3)) }
		}

	@Test
	fun `finds the one tooltip for a category and tag`() {
		queryTooltip(db, category = "java", tag = "tag7").use { c ->
			assertEquals(1, c.count)
			c.moveToFirst()
			assertEquals(1000 + 2 * 500 + 8, c.getInt(0))
			assertEquals("summary 3/7", c.getString(1))
			assertEquals("detail", c.getString(2))
		}
		queryTooltip(db, category = "java", tag = "no-such-tag").use { assertEquals(0, it.count) }
		queryTooltip(db, category = "no-such-category", tag = "tag7").use { assertEquals(0, it.count) }
	}

	@Test
	fun `two categories with the same name still come back as two rows`() {
		db.execSQL("INSERT INTO TooltipCategories VALUES (5, 'java')")
		db.execSQL("INSERT INTO TooltipTexts VALUES (9999, 'duplicate', 'detail')")
		db.execSQL("INSERT INTO Tooltips VALUES (9999, 5, 'tag7')")

		queryTooltip(db, category = "java", tag = "tag7").use { assertEquals(2, it.count) }
	}

	@Test
	fun `finds the tag by Tooltips' key and the text by TooltipTexts' id, scanning neither`() {
		val plan = plan(QUERY_TOOLTIP, "java", "tag7")

		assertTrue(plan.toString(), plan.any { Regex("""\bUSING PRIMARY KEY \(categoryId=\? AND tag=\?\)""").containsMatchIn(it) })
		assertTrue(plan.toString(), plan.any { Regex("""\b(X|TooltipTexts)\b.*USING INTEGER PRIMARY KEY""").containsMatchIn(it) })
		assertFalse(plan.toString(), plan.any { Regex("""\bSCAN (TABLE )?(T|X|Tooltips|TooltipTexts)\b""").containsMatchIn(it) })
	}

	@Test
	fun `returns a tooltip's buttons in button order with their text`() {
		val id = 1000 + 2 * 500 + 8

		assertEquals(
			listOf("View full documentation" to "page$id.html", "Examples" to "page$id.html"),
			queryTooltipButtons(db, id),
		)
		assertEquals(emptyList<Pair<String, String>>(), queryTooltipButtons(db, 1))
	}

	@Test
	fun `finds the buttons by TooltipButtons' key, already in order, and their text by id`() {
		val plan = plan(QUERY_TOOLTIP_BUTTONS, "1009")

		assertTrue(plan.toString(), plan.any { Regex("""\bUSING PRIMARY KEY \(tooltipId=\?\)""").containsMatchIn(it) })
		assertFalse(plan.toString(), plan.any { "TEMP B-TREE" in it })
		assertFalse(plan.toString(), plan.any { Regex("""\bSCAN (TABLE )?\w+""").containsMatchIn(it) })
	}

	@Test
	fun `reads a tooltip's id, text and buttons into the item TooltipManager shows`() {
		db.execSQL("INSERT INTO TooltipTexts VALUES (4242, 'Declares a read-only property.', 'Longer detail.')")
		db.execSQL("INSERT INTO Tooltips VALUES (4242, 4, 'val')")
		db.execSQL("INSERT INTO TooltipButtonDescriptions VALUES (3, 'Learn more about properties')")
		db.execSQL("INSERT INTO TooltipButtonUris VALUES (9001, 'k/html/properties.html'), (9002, 'k/html/basic-syntax.html')")
		db.execSQL("INSERT INTO TooltipButtons VALUES (4242, 2, 2, 9002), (4242, 1, 3, 9001)")

		val item = readTooltip(db, category = "kotlin", tag = "val", lastChange = "2026-10-18 (test)")

		assertEquals(
			IDETooltipItem(
				rowId = 4242,
				id = 4242,
				category = "kotlin",
				tag = "val",
				summary = "Declares a read-only property.",
				detail = "Longer detail.",
				buttons =
					arrayListOf(
						"Learn more about properties" to "http://localhost:6174/k/html/properties.html",
						"Examples" to "http://localhost:6174/k/html/basic-syntax.html",
					),
				lastChange = "2026-10-18 (test)",
			),
			item,
		)
	}

	@Test
	fun `reading a tag with no tooltip throws NoTooltipFoundException`() {
		assertThrows(NoTooltipFoundException::class.java) {
			readTooltip(db, category = "java", tag = "no-such-tag", lastChange = "test")
		}
	}

	@Test
	fun `reading a tag two same-named categories hold throws DatabaseCorruptionException`() {
		db.execSQL("INSERT INTO TooltipCategories VALUES (5, 'java')")
		db.execSQL("INSERT INTO TooltipTexts VALUES (9999, 'duplicate', 'detail')")
		db.execSQL("INSERT INTO Tooltips VALUES (9999, 5, 'tag7')")

		assertThrows(DatabaseCorruptionException::class.java) {
			readTooltip(db, category = "java", tag = "tag7", lastChange = "test")
		}
	}

	@Test
	fun `refuses a database that declares a version before 3`() {
		setVersion(2)

		val e = assertThrows(IllegalStateException::class.java) { queryTooltip(db, category = "java", tag = "tag7") }
		assertTrue(e.message, "version 2" in e.message.orEmpty())
	}

	@Test
	fun `refuses a database that declares no version`() {
		db.execSQL("DROP TABLE DocumentationDatabaseVersion")

		val e = assertThrows(IllegalStateException::class.java) { queryTooltip(db, category = "java", tag = "tag7") }
		assertTrue(e.message, "version none" in e.message.orEmpty())
	}

	@Test
	fun `reads a database that declares a later version`() {
		setVersion(4)

		queryTooltip(db, category = "java", tag = "tag7").use { assertEquals(1, it.count) }
	}

	private companion object {
		// documentation.db's version 3 tooltip tables, as migrate_tooltip_schema.py creates them.
		val SCHEMA =
			listOf(
				"CREATE TABLE TooltipCategories (id INTEGER PRIMARY KEY, category TEXT NOT NULL)",
				"CREATE TABLE TooltipTexts (id INTEGER PRIMARY KEY AUTOINCREMENT, summary TEXT NOT NULL, detail TEXT NOT NULL)",
				"CREATE TABLE Tooltips (id INTEGER NOT NULL, categoryId INTEGER NOT NULL, tag TEXT NOT NULL, " +
					"PRIMARY KEY (categoryId, tag), FOREIGN KEY(categoryId) REFERENCES TooltipCategories(id), " +
					"FOREIGN KEY(id) REFERENCES TooltipTexts(id)) WITHOUT ROWID",
				"CREATE TABLE TooltipButtonDescriptions (id INTEGER PRIMARY KEY, description TEXT NOT NULL)",
				"CREATE TABLE TooltipButtonUris (id INTEGER PRIMARY KEY, uri TEXT NOT NULL)",
				"CREATE TABLE TooltipButtons (tooltipId INTEGER NOT NULL, buttonNumberId INTEGER NOT NULL, " +
					"descriptionId INTEGER NOT NULL, uriId INTEGER NOT NULL, PRIMARY KEY (tooltipId, buttonNumberId), " +
					"FOREIGN KEY(tooltipId) REFERENCES TooltipTexts(id), " +
					"FOREIGN KEY(descriptionId) REFERENCES TooltipButtonDescriptions(id), " +
					"FOREIGN KEY(uriId) REFERENCES TooltipButtonUris(id)) WITHOUT ROWID",
				"CREATE TABLE DocumentationDatabaseVersion (major INT NOT NULL, minor INT NOT NULL, patch INT NOT NULL, " +
					"who TEXT NOT NULL, comment TEXT NOT NULL, changeTime TIMESTAMP DEFAULT CURRENT_TIMESTAMP)",
			)
	}
}
