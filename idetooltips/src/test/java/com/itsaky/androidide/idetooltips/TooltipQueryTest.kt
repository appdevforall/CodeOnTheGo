package com.itsaky.androidide.idetooltips

import android.database.sqlite.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Runs [queryTooltip], the lookup TooltipManager.getTooltip makes, against documentation.db's own
 * Tooltips schema. The query plan matters as much as the rows: the join it replaced scanned the
 * whole 13 MB table for every tooltip shown (ADFA-6597).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class TooltipQueryTest {
	private lateinit var db: SQLiteDatabase

	@Before
	fun setUp() {
		db = SQLiteDatabase.create(null)
		db.execSQL("CREATE TABLE TooltipCategories (id INTEGER PRIMARY KEY, category TEXT NOT NULL)")
		db.execSQL(
			"CREATE TABLE Tooltips (id INTEGER PRIMARY KEY AUTOINCREMENT, categoryId INTEGER NOT NULL, " +
				"tag TEXT NOT NULL, summary TEXT NOT NULL, detail TEXT NOT NULL, UNIQUE (categoryId, tag), " +
				"FOREIGN KEY(categoryId) REFERENCES TooltipCategories(id))",
		)
		listOf("ide", "xml", "java", "kotlin").forEachIndexed { i, category ->
			db.execSQL("INSERT INTO TooltipCategories VALUES (?, ?)", arrayOf(i + 1, category))
		}
		db.beginTransaction()
		for (categoryId in 1..4) {
			for (n in 0 until 500) {
				db.execSQL(
					"INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (?, ?, ?, ?)",
					arrayOf(categoryId, "tag$n", "summary $categoryId/$n", "detail"),
				)
			}
		}
		db.setTransactionSuccessful()
		db.endTransaction()
	}

	@After
	fun tearDown() {
		db.close()
	}

	@Test
	fun `finds the one tooltip for a category and tag`() {
		queryTooltip(db, category = "java", tag = "tag7").use { c ->
			assertEquals(1, c.count)
			c.moveToFirst()
			assertEquals("summary 3/7", c.getString(2))
		}
		queryTooltip(db, category = "java", tag = "no-such-tag").use { assertEquals(0, it.count) }
		queryTooltip(db, category = "no-such-category", tag = "tag7").use { assertEquals(0, it.count) }
	}

	@Test
	fun `two categories with the same name still come back as two rows`() {
		db.execSQL("INSERT INTO TooltipCategories VALUES (5, 'java')")
		db.execSQL("INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (5, 'tag7', 'duplicate', 'detail')")

		queryTooltip(db, category = "java", tag = "tag7").use { assertEquals(2, it.count) }
	}

	@Test
	fun `finds the tag through the (categoryId, tag) index, not a scan of Tooltips`() {
		val plan =
			db.rawQuery("EXPLAIN QUERY PLAN " + QUERY_TOOLTIP, arrayOf("java", "tag7")).use { c ->
				buildList { while (c.moveToNext()) add(c.getString(3)) }
			}

		assertTrue(plan.toString(), plan.any { "sqlite_autoindex_Tooltips_1" in it })
		assertFalse(plan.toString(), plan.any { Regex("""\bSCAN (TABLE )?(T|Tooltips)\b""").containsMatchIn(it) })
	}
}
