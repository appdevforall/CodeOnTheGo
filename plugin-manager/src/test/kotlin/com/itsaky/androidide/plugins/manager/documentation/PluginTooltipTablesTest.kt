package com.itsaky.androidide.plugins.manager.documentation

import android.database.SQLException
import android.database.sqlite.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Runs [writePluginTooltips] and [removePluginTooltips] against documentation.db's version 3
 * tooltip schema (ADFA-3305), as migrate_tooltip_schema.py in OfflineDocumentationTools writes it,
 * holding one built-in tooltip that plugins must never disturb. What a plugin writes is read back
 * with the queries the app runs (idetooltips' TooltipQuery.kt).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class PluginTooltipTablesTest {
	private lateinit var db: SQLiteDatabase

	@Before
	fun setUp() {
		db = SQLiteDatabase.create(null)
		for (statement in SCHEMA) db.execSQL(statement)
		setVersion(3)
		db.execSQL("INSERT INTO TooltipCategories VALUES (1, 'ide')")
		db.execSQL("INSERT INTO TooltipTexts VALUES ($BUILT_IN_ID, 'built-in summary', 'built-in detail')")
		db.execSQL("INSERT INTO Tooltips VALUES ($BUILT_IN_ID, 1, 'editor')")
		db.execSQL("INSERT INTO TooltipButtonDescriptions VALUES (1, '$SHARED_DESCRIPTION')")
		db.execSQL("INSERT INTO TooltipButtonUris VALUES (1, 'a/editor.html')")
		db.execSQL("INSERT INTO TooltipButtons VALUES ($BUILT_IN_ID, 1, 1, 1)")
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

	private fun inTransaction(block: () -> Unit) {
		db.beginTransaction()
		try {
			block()
			db.setTransactionSuccessful()
		} finally {
			db.endTransaction()
		}
	}

	private fun write(
		category: String,
		vararg tooltips: PluginTooltipRow,
	) = inTransaction { writePluginTooltips(db, category, tooltips.toList()) }

	private fun remove(category: String) = inTransaction { removePluginTooltips(db, category) }

	/** What the app shows for (category, tag): id, summary, detail and buttons, or null if nothing. */
	private fun shown(
		category: String,
		tag: String,
	): Shown? {
		val tooltip =
			db.rawQuery(APP_TOOLTIP_QUERY, arrayOf(category, tag)).use { c ->
				assertTrue("more than one row for $category/$tag", c.count <= 1)
				if (c.moveToFirst()) Triple(c.getLong(0), c.getString(1), c.getString(2)) else null
			} ?: return null
		val buttons =
			db.rawQuery(APP_BUTTONS_QUERY, arrayOf(tooltip.first.toString())).use { c ->
				buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) }
			}
		return Shown(tooltip.first, tooltip.second, tooltip.third, buttons)
	}

	private data class Shown(
		val id: Long,
		val summary: String,
		val detail: String,
		val buttons: List<Pair<String, String>>,
	)

	private fun count(table: String): Long =
		db.rawQuery("SELECT COUNT(*) FROM $table", null).use {
			it.moveToFirst()
			it.getLong(0)
		}

	private fun contents(): Map<String, List<List<String?>>> =
		TABLES.associateWith { table ->
			db.rawQuery("SELECT * FROM $table ORDER BY 1, 2", null).use { c ->
				buildList { while (c.moveToNext()) add((0 until c.columnCount).map { c.getString(it) }) }
			}
		}

	private val builtIn = Shown(BUILT_IN_ID, "built-in summary", "built-in detail", listOf(SHARED_DESCRIPTION to "a/editor.html"))

	private fun row(
		tag: String,
		summary: String = "summary of $tag",
		detail: String = "detail of $tag",
		vararg buttons: Pair<String, String>,
	) = PluginTooltipRow(tag, summary, detail, buttons.map { PluginTooltipButtonRow(it.first, it.second) })

	@Test
	fun `writes tooltips the app reads back with their buttons in order`() {
		write(
			"plugin_x",
			row("x.one", buttons = arrayOf("Read more" to "plugin/x/index.html", "Overview" to "i/plugins-adfa.html")),
			row("x.two", detail = ""),
		)

		val one = shown("plugin_x", "x.one")!!
		assertEquals("summary of x.one", one.summary)
		assertEquals("detail of x.one", one.detail)
		assertEquals(listOf("Read more" to "plugin/x/index.html", "Overview" to "i/plugins-adfa.html"), one.buttons)
		val two = shown("plugin_x", "x.two")!!
		assertEquals("", two.detail)
		assertEquals(emptyList<Pair<String, String>>(), two.buttons)
		assertNotEquals(one.id, two.id)
		assertEquals(builtIn, shown("ide", "editor"))
	}

	@Test
	fun `gives each new tooltip an id no tooltip has had`() {
		write("plugin_x", row("x.one"), row("x.two"))
		val first = listOf(shown("plugin_x", "x.one")!!.id, shown("plugin_x", "x.two")!!.id)
		remove("plugin_x")
		write("plugin_x", row("x.one"))

		val again = shown("plugin_x", "x.one")!!.id
		assertTrue("$first, then $again", first.all { it > BUILT_IN_ID } && again > first.max())
	}

	@Test
	fun `keeps the later entry for a tag listed twice`() {
		write(
			"plugin_x",
			row("x.one", summary = "first", buttons = arrayOf("First" to "plugin/x/1.html")),
			row("x.one", summary = "second", buttons = arrayOf("Second" to "plugin/x/2.html")),
		)

		val one = shown("plugin_x", "x.one")!!
		assertEquals("second", one.summary)
		assertEquals(listOf("Second" to "plugin/x/2.html"), one.buttons)
		assertEquals(2L, count("Tooltips"))
		assertEquals(2L, count("TooltipTexts"))
		// "First" and plugin/x/1.html were looked up for the first entry; nothing uses them now.
		assertEquals(listOf("Second", SHARED_DESCRIPTION), column("TooltipButtonDescriptions", "description"))
		assertEquals(listOf("a/editor.html", "plugin/x/2.html"), column("TooltipButtonUris", "uri"))
	}

	private fun column(
		table: String,
		column: String,
	): List<String> =
		db.rawQuery("SELECT $column FROM $table ORDER BY $column", null).use { c ->
			buildList { while (c.moveToNext()) add(c.getString(0)) }
		}

	@Test
	fun `stores each button text once, sharing what built-in buttons use`() {
		write(
			"plugin_x",
			row("x.one", buttons = arrayOf(SHARED_DESCRIPTION to "a/editor.html", "Read more" to "plugin/x/a.html")),
			row("x.two", buttons = arrayOf("Read more" to "plugin/x/a.html")),
		)

		assertEquals(listOf("Read more", SHARED_DESCRIPTION), column("TooltipButtonDescriptions", "description"))
		assertEquals(listOf("a/editor.html", "plugin/x/a.html"), column("TooltipButtonUris", "uri"))
		assertEquals(listOf(SHARED_DESCRIPTION to "a/editor.html", "Read more" to "plugin/x/a.html"), shown("plugin_x", "x.one")!!.buttons)
	}

	@Test
	fun `removing a plugin leaves the database as it was before the plugin wrote`() {
		val before = contents()
		write(
			"plugin_x",
			row("x.one", buttons = arrayOf(SHARED_DESCRIPTION to "plugin/x/a.html", "Read more" to "a/editor.html")),
		)
		write("plugin_y", row("y.one", buttons = arrayOf("Read more" to "plugin/y/a.html")))
		remove("plugin_x")

		assertEquals(null, shown("plugin_x", "x.one"))
		assertEquals(listOf("Read more" to "plugin/y/a.html"), shown("plugin_y", "y.one")!!.buttons)
		remove("plugin_y")

		assertEquals(before, contents())
		assertEquals(builtIn, shown("ide", "editor"))
	}

	@Test
	fun `writing again replaces the plugin's tooltips rather than adding to them`() {
		write("plugin_x", row("x.one", buttons = arrayOf("Read more" to "plugin/x/a.html")), row("x.two"))
		write("plugin_x", row("x.one", summary = "new", buttons = arrayOf("Overview" to "plugin/x/b.html")))

		assertEquals(null, shown("plugin_x", "x.two"))
		assertEquals(listOf("Overview" to "plugin/x/b.html"), shown("plugin_x", "x.one")!!.buttons)
		assertEquals(2L, count("Tooltips"))
		assertEquals(2L, count("TooltipTexts"))
		assertEquals(2L, count("TooltipButtons"))
		assertEquals(listOf("Overview", SHARED_DESCRIPTION), column("TooltipButtonDescriptions", "description"))
		assertEquals(listOf("a/editor.html", "plugin/x/b.html"), column("TooltipButtonUris", "uri"))
	}

	// Three lookup chunks. Robolectric's SQLite binds more than 33,000 variables, so this cannot show
	// that the chunks keep under SQLite's pre-3.32 default limit of 999; only a device's SQLite can.
	@Test
	fun `writes and removes 1,200 tooltips at once`() {
		val many = (1..1200).map { row("x.$it", buttons = arrayOf("Read $it" to "plugin/x/$it.html")) }
		write("plugin_x", *many.toTypedArray())

		assertEquals(listOf("Read 1100" to "plugin/x/1100.html"), shown("plugin_x", "x.1100")!!.buttons)
		assertEquals(1201L, count("TooltipButtonUris"))
		remove("plugin_x")
		assertEquals(1L, count("Tooltips"))
		assertEquals(1L, count("TooltipButtonUris"))
	}

	@Test
	fun `refuses a database before version 3 and writes nothing`() {
		setVersion(2)
		val before = contents()

		val written = assertThrows(IllegalStateException::class.java) { write("plugin_x", row("x.one")) }
		assertTrue(written.message, "version 2" in written.message.orEmpty())
		val removed = assertThrows(IllegalStateException::class.java) { remove("ide") }
		assertTrue(removed.message, "version 2" in removed.message.orEmpty())
		assertEquals(before, contents())
	}

	@Test
	fun `throws when any insert fails rather than carry on without the row`() {
		for (table in listOf(
			"TooltipCategories",
			"TooltipTexts",
			"Tooltips",
			"TooltipButtonDescriptions",
			"TooltipButtonUris",
			"TooltipButtons",
		)) {
			db.execSQL("CREATE TRIGGER refuse BEFORE INSERT ON $table BEGIN SELECT RAISE(ABORT, 'refused'); END")
			val before = contents()

			assertThrows(table, SQLException::class.java) {
				write("plugin_x", row("x.one", buttons = arrayOf("Read more" to "plugin/x/a.html")))
			}
			assertEquals(table, before, contents())
			db.execSQL("DROP TRIGGER refuse")
		}
	}

	private companion object {
		const val BUILT_IN_ID = 500L
		const val SHARED_DESCRIPTION = "View full documentation"

		val TABLES =
			listOf(
				"TooltipCategories",
				"TooltipTexts",
				"Tooltips",
				"TooltipButtonDescriptions",
				"TooltipButtonUris",
				"TooltipButtons",
			)

		// What idetooltips' TooltipQuery.kt runs.
		const val APP_TOOLTIP_QUERY =
			"SELECT T.id, X.summary, X.detail FROM Tooltips AS T JOIN TooltipTexts AS X ON X.id = T.id " +
				"WHERE T.categoryId IN (SELECT id FROM TooltipCategories WHERE category = ?) AND T.tag = ?"
		const val APP_BUTTONS_QUERY =
			"SELECT D.description, U.uri FROM TooltipButtons AS B " +
				"JOIN TooltipButtonDescriptions AS D ON D.id = B.descriptionId " +
				"JOIN TooltipButtonUris AS U ON U.id = B.uriId WHERE B.tooltipId = ? ORDER BY B.buttonNumberId"

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
