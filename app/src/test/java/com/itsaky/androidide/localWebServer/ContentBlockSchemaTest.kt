package com.itsaky.androidide.localWebServer

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import com.itsaky.androidide.documentation.DocumentationContentSource
import com.itsaky.androidide.documentation.DocumentationLookup
import com.itsaky.androidide.utils.BrotliDictionaryCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The documentation version 4 schema (ADFA-6683) in a real SQLite file, read through
 * [DocumentationContentSource]. ContentBlockSourceTest's mocks never run the SQL; this does: the
 * join to ContentBlockItems, its quoted `offset` column, and SQLite's own error for a version 4
 * file without the block tables.
 */
@RunWith(RobolectricTestRunner::class)
class ContentBlockSchemaTest {
	@get:Rule
	val folder = TemporaryFolder()

	@Test
	fun `a version 4 file serves a blocked page from its block and an unblocked one from its row`() {
		val file =
			database(major = 4, blockTables = true) {
				block(1, "<p>One</p><p>Two</p>")
				row(10, "j/one.html", blocked = true)
				row(11, "j/two.html", blocked = true)
				item(10, blockId = 1, offset = 0, length = 10)
				item(11, blockId = 1, offset = 10, length = 10)
				row(12, "j/own.html", text = "<p>Own</p>")
			}
		val source = source(file)

		assertEquals("<p>Two</p>", text(source.lookup("j/two.html")))
		assertEquals("<p>One</p>", text(source.lookup("j/one.html")))
		assertEquals("<p>Own</p>", text(source.lookup("j/own.html")))
	}

	@Test
	fun `a version 4 file without the block tables fails with SQLite's error`() {
		val file = database(major = 4, blockTables = false) { row(12, "j/own.html", text = "<p>Own</p>") }

		val lookup = source(file).lookup("j/own.html")

		val cause = (lookup as? DocumentationLookup.Failed)?.cause ?: throw AssertionError("expected Failed, got $lookup")
		assertTrue(cause.toString(), cause is SQLiteException)
		assertTrue(cause.toString(), cause.message.orEmpty().contains("no such table: ContentBlockItems"))
	}

	@Test
	fun `a version 3 file reads its rows' own content, even with the block tables present`() {
		val file =
			database(major = 3, blockTables = true) {
				block(1, "<p>Block</p>")
				row(10, "j/one.html", text = "<p>Row</p>")
				item(10, blockId = 1, offset = 0, length = 12)
			}

		assertEquals("<p>Row</p>", text(source(file).lookup("j/one.html")))
	}

	private val codec = BrotliDictionaryCodec(null)

	private fun source(file: File) = DocumentationContentSource(file, File(folder.root, "debug-documentation.db"), 0)

	private fun text(lookup: DocumentationLookup): String =
		((lookup as? DocumentationLookup.Found) ?: throw AssertionError("expected Found, got $lookup"))
			.content.bytes
			.toString(Charsets.UTF_8)

	/** A file declaring [major], with one Brotli content type, no dictionary, and [fill]'s rows. */
	private fun database(
		major: Int,
		blockTables: Boolean,
		fill: SQLiteDatabase.() -> Unit,
	): File {
		val file = File(folder.root, "documentation.db")
		SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
			db.execSQL("CREATE TABLE ContentTypes (id INTEGER PRIMARY KEY, value TEXT NOT NULL, compression TEXT NOT NULL)")
			db.execSQL(
				"CREATE TABLE Content (id INTEGER PRIMARY KEY AUTOINCREMENT, path TEXT NOT NULL, " +
					"languageID INTEGER NOT NULL, content BLOB NOT NULL, contentTypeID INTEGER NOT NULL, " +
					"templateId INTEGER, UNIQUE(path))",
			)
			db.execSQL(
				"CREATE TABLE DocumentationDatabaseVersion (major INTEGER, minor INTEGER, patch INTEGER, " +
					"who TEXT, comment TEXT, changeTime TEXT)",
			)
			db.execSQL("INSERT INTO DocumentationDatabaseVersion VALUES (?, 0, 0, 'test', 'test', '2026-10-26')", arrayOf(major))
			db.execSQL("INSERT INTO ContentTypes VALUES (1, 'text/html', 'brotli')")
			if (blockTables) {
				// The 4.0.0 DDL, as OfflineDocumentationTools' store_pages_in_blocks.py writes it.
				db.execSQL("CREATE TABLE ContentBlocks (id INTEGER PRIMARY KEY AUTOINCREMENT, data BLOB NOT NULL)")
				db.execSQL(
					"CREATE TABLE ContentBlockItems (" +
						"contentId INTEGER NOT NULL PRIMARY KEY REFERENCES Content(id), " +
						"blockId INTEGER NOT NULL REFERENCES ContentBlocks(id), " +
						"offset INTEGER NOT NULL, length INTEGER NOT NULL) WITHOUT ROWID",
				)
			}
			db.fill()
		}
		return file
	}

	private fun SQLiteDatabase.block(
		id: Long,
		text: String,
	) = execSQL("INSERT INTO ContentBlocks (id, data) VALUES (?, ?)", arrayOf<Any>(id, codec.compress(text.toByteArray())))

	/** A Content row: [text] Brotli-compressed, or the empty content a blocked row has. */
	private fun SQLiteDatabase.row(
		id: Long,
		path: String,
		text: String? = null,
		blocked: Boolean = false,
	) {
		check(blocked == (text == null))
		val content = text?.let { codec.compress(it.toByteArray()) } ?: ByteArray(0)
		execSQL(
			"INSERT INTO Content (id, path, languageID, content, contentTypeID, templateId) VALUES (?, ?, 1, ?, 1, 0)",
			arrayOf<Any>(id, path, content),
		)
	}

	private fun SQLiteDatabase.item(
		contentId: Long,
		blockId: Long,
		offset: Long,
		length: Long,
	) = execSQL(
		"INSERT INTO ContentBlockItems (contentId, blockId, \"offset\", length) VALUES (?, ?, ?, ?)",
		arrayOf<Any>(contentId, blockId, offset, length),
	)
}
