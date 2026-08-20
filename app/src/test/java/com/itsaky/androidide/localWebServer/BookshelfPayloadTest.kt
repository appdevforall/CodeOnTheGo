package com.itsaky.androidide.localWebServer

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Test

/**
 * Covers the bookshelf payload now that it is assembled in Kotlin rather than by SQLite's JSON1
 * functions (ADFA-5179), which are missing from the system SQLite on some devices.
 *
 * The shape matters as much as the content: the `bookshelf` Pebble template was written against what
 * `JSON_OBJECT`/`JSON_GROUP_ARRAY` emitted, so the keys, the nesting, the explicit nulls and the 1/0
 * `pdf` flag all have to survive the change.
 */
class BookshelfPayloadTest {
	// Without this, mockk's instrumentation outlives the class and breaks a later test in the same
	// JVM: BrotliDictionaryDecodeTest's @BeforeClass then fails to load the brotli native library.
	@After
	fun tearDown() {
		unmockkAll()
	}

	private fun server() =
		WebServer(
			ServerConfig(
				port = 0,
				databasePath = "/nonexistent/test.db",
				fileDirPath = "/tmp",
				debugDatabasePath = "/nonexistent/debug.db",
				debugEnablePath = "/nonexistent/debug-flag",
				experimentsEnablePath = "/nonexistent/exp-flag",
				clearCacheEnablePath = "/nonexistent/cs0-flag",
				projectDatabasePath = "/nonexistent/recent-projects.db",
			),
		)

	/** One joined row: category, category description, title, book description, path. */
	private fun database(vararg rows: Array<String?>): SQLiteDatabase {
		var index = -1
		val cursor =
			mockk<Cursor>(relaxed = true) {
				every { moveToNext() } answers { ++index < rows.size }
				every { getString(any()) } answers { rows[index][firstArg<Int>()] }
			}

		return mockk(relaxed = true) {
			every { rawQuery(match { it.contains("FROM Content AS C") }, any()) } returns cursor
		}
	}

	@Test
	fun `books are grouped into the categories the query ordered them by`() {
		val bookshelf =
			server().readBookshelf(
				database(
					arrayOf("Java", "Books about Java", "Effective Java", "A classic", "j/effective.html"),
					arrayOf("Java", "Books about Java", "Java Concurrency", "Also good", "j/concurrency.html"),
					arrayOf("Kotlin", "Books about Kotlin", "Kotlin in Action", "Recommended", "k/in-action.html"),
				),
			)

		assertThat(bookshelf.result.map { it.category }).containsExactly("Java", "Kotlin").inOrder()
		assertThat(bookshelf.result[0].description).isEqualTo("Books about Java")
		assertThat(bookshelf.result[0].books.map { it.title })
			.containsExactly("Effective Java", "Java Concurrency")
			.inOrder()
		assertThat(
			bookshelf.result[1]
				.books
				.single()
				.link,
		).isEqualTo("k/in-action.html")
	}

	@Test
	fun `a pdf link is flagged with 1, anything else with 0`() {
		val bookshelf =
			server().readBookshelf(
				database(
					arrayOf("General", "", "A guide", "", "d/guide.pdf"),
					arrayOf("General", "", "Shouty guide", "", "d/GUIDE.PDF"),
					arrayOf("General", "", "A page", "", "i/index.html"),
				),
			)

		assertThat(
			bookshelf.result
				.single()
				.books
				.map { it.pdf },
		).containsExactly(1, 1, 0).inOrder()
	}

	@Test
	fun `a category row with no label files its books under General`() {
		// BookCategories.category is nullable, so this is reachable; a book with no category at all
		// is a different case, dropped by the join exactly as the query this replaced dropped it.
		val bookshelf =
			server().readBookshelf(
				database(arrayOf(null, "No label", "A guide", "", "d/guide.pdf")),
			)

		assertThat(bookshelf.result.single().category).isEqualTo("General")
		assertThat(
			bookshelf.result
				.single()
				.books
				.single()
				.title,
		).isEqualTo("A guide")
	}

	@Test
	fun `a book with no title of its own shows its path`() {
		val bookshelf =
			server().readBookshelf(
				database(arrayOf("General", "", null, "", "i/index.html")),
			)

		assertThat(
			bookshelf.result
				.single()
				.books
				.single()
				.title,
		).isEqualTo("i/index.html")
	}

	@Test
	fun `an empty bookshelf is an empty list, not a failure`() {
		// The old query made this an HTTP 500: group_concat over no rows is NULL, and reading that
		// as a blob threw. At least one documentation.db copy joins to nothing, so it is reachable.
		val bookshelf = server().readBookshelf(database())

		assertThat(bookshelf.result).isEmpty()
	}

	@Test
	fun `the JSON keeps the keys, nesting and explicit nulls the template was written against`() {
		val json =
			server().gsonForTest.toJson(
				server().readBookshelf(
					database(arrayOf("General", null, "A guide", null, "d/guide.pdf")),
				),
			)

		assertThat(json).isEqualTo(
			"""{"result":[{"category":"General","description":null,""" +
				""""books":[{"title":"A guide","description":null,"link":"d/guide.pdf","pdf":1}]}]}""",
		)
	}
}
