/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.localWebServer

import android.database.sqlite.SQLiteDatabase
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Runs the real query against a real SQLite database.
 *
 * The sibling `BookshelfPayloadTest` hands `readBookshelf` canned cursor rows, so the SQL itself --
 * its column order, its joins, its ORDER BY -- is never executed there. On a ticket whose whole
 * subject is a query that passed on a desktop and failed on a device, that gap is the one worth
 * closing: the SELECT list has two columns both named `description` (`BC.description` at index 1,
 * `B.description` at index 3) read by bare positional index, so inserting or reordering a column
 * silently swaps category descriptions onto books, and a mock encoding the same convention shifts
 * with it and keeps passing.
 */
@RunWith(RobolectricTestRunner::class)
class BookshelfQueryTest {
	private lateinit var database: SQLiteDatabase

	@Before
	fun setUp() {
		database = SQLiteDatabase.create(null)
		database.execSQL("CREATE TABLE ContentTypes (id INTEGER PRIMARY KEY, value TEXT, compression TEXT)")
		database.execSQL(
			"CREATE TABLE Content (id INTEGER PRIMARY KEY, path TEXT, languageID INTEGER, " +
				"content BLOB, contentTypeID INTEGER, templateId INTEGER)",
		)
		database.execSQL("CREATE TABLE BookCategories (id INTEGER PRIMARY KEY, category TEXT, description TEXT)")
		database.execSQL(
			"CREATE TABLE Bookshelf (contentID INTEGER, bookCategoryID INTEGER, title TEXT, description TEXT)",
		)
	}

	@After
	fun tearDown() {
		database.close()
	}

	private fun book(
		id: Int,
		path: String,
		categoryId: Int?,
		title: String?,
		bookDescription: String?,
	) {
		database.execSQL("INSERT INTO Content (id, path) VALUES (?, ?)", arrayOf<Any>(id, path))
		database.execSQL(
			"INSERT INTO Bookshelf (contentID, bookCategoryID, title, description) VALUES (?, ?, ?, ?)",
			arrayOf<Any?>(id, categoryId, title, bookDescription),
		)
	}

	private fun category(
		id: Int,
		name: String?,
		description: String?,
	) = database.execSQL(
		"INSERT INTO BookCategories (id, category, description) VALUES (?, ?, ?)",
		arrayOf<Any?>(id, name, description),
	)

	// The two description columns are the thing this pins: index 1 is the category's, index 3 is the
	// book's. Reading them the other way round is invisible to a mock that encodes the same order.
	@Test
	fun `the category description and the book description do not swap`() {
		category(1, "Java", "Books about Java")
		book(10, "d/notes.pdf", 1, "Java Notes", "Compiled from Stack Overflow")

		val shelf = WebServer(testServerConfig()).readBookshelf(database)

		val java = shelf.result.single()
		assertThat(java.description).isEqualTo("Books about Java")
		assertThat(java.books.single().description).isEqualTo("Compiled from Stack Overflow")
		assertThat(java.books.single().link).isEqualTo("d/notes.pdf")
	}

	// The join is inner, deliberately: a book with no category is not on the shelf, which is what the
	// JSON1 query did. It is also why the template's General section is reachable only for a category
	// row that exists but has no label.
	@Test
	fun `a book with no category is not on the shelf`() {
		category(1, "Java", null)
		book(10, "d/a.pdf", 1, "Has a category", null)
		book(11, "d/b.pdf", null, "Has none", null)

		val shelf = WebServer(testServerConfig()).readBookshelf(database)

		assertThat(
			shelf.result
				.single()
				.books
				.map { it.title },
		).containsExactly("Has a category")
	}

	// A category row that exists but has no label is the case IFNULL(BC.category, 'General') covered.
	@Test
	fun `a category row with no label files its books under General`() {
		category(1, null, null)
		book(10, "d/a.pdf", 1, "Unlabelled", null)

		val shelf = WebServer(testServerConfig()).readBookshelf(database)

		assertThat(shelf.result.single().category).isEqualTo("General")
	}

	// Books come back sorted by title, which the JSON1 version did not do -- see readBookshelf's KDoc.
	@Test
	fun `books within a category come back ordered by title`() {
		category(1, "Java", null)
		book(11, "d/b.pdf", 1, "Java, Java, Java", null)
		book(10, "d/a.pdf", 1, "Java Notes", null)

		val shelf = WebServer(testServerConfig()).readBookshelf(database)

		assertThat(
			shelf.result
				.single()
				.books
				.map { it.title },
		).containsExactly("Java Notes", "Java, Java, Java")
			.inOrder()
	}

	// The sort key has to be the string the page shows, not the raw column: a NULL title displays as
	// its path, and SQLite's BINARY collation would otherwise file every capitalised title ahead of
	// every lower-case one.
	@Test
	fun `books are ordered by what the page displays, case-insensitively`() {
		category(1, "Mixed", null)
		book(10, "d/zebra.pdf", 1, null, null)
		book(11, "d/b.pdf", 1, "Android Basics", null)
		book(12, "d/c.pdf", 1, "apple guide", null)

		val titles =
			WebServer(testServerConfig())
				.readBookshelf(database)
				.result
				.single()
				.books
				.map { it.title }

		assertThat(titles).containsExactly("Android Basics", "apple guide", "d/zebra.pdf").inOrder()
	}

	// A row with no path cannot be linked, so it is skipped -- and with it goes its category, if that
	// was the only book in it. Pinned because it is a behaviour change the old query did not make.
	@Test
	fun `a category whose only book has no path disappears from the shelf`() {
		category(1, "Reference", null)
		book(10, "unused", 1, "Broken", null)
		database.execSQL("UPDATE Content SET path = NULL WHERE id = 10")

		val shelf = WebServer(testServerConfig()).readBookshelf(database)

		assertThat(shelf.result).isEmpty()
	}

	// The empty payload must stay renderable: instantiatePebbleTemplate throws for a blank or "null"
	// context, and that guard sits outside realHandleBsEndpoint's try/catch, so a blank here would be
	// a 500 rather than the empty page this ticket promises.
	@Test
	fun `an empty shelf serialises to a renderable context, not blank or null`() {
		val json = String(WebServer(testServerConfig()).bookshelfJson(database), Charsets.UTF_8)

		assertThat(json).isEqualTo("""{"result":[]}""")
		assertThat(json.isBlank()).isFalse()
		assertThat(json.trim()).isNotEqualTo("null")
	}

	@Test
	fun `an empty database yields an empty shelf rather than throwing`() {
		val shelf = WebServer(testServerConfig()).readBookshelf(database)

		assertThat(shelf.result).isEmpty()
	}
}
