package com.itsaky.androidide.localWebServer

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.itsaky.androidide.documentation.DocumentationContent
import com.itsaky.androidide.documentation.DocumentationContentSource
import com.itsaky.androidide.documentation.DocumentationLookup
import com.itsaky.androidide.logging.provider.IdeLogRouter
import com.itsaky.androidide.utils.BrotliDictionaryCodec
import com.itsaky.androidide.utils.toDirectByteBuffer
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.slf4j.event.Level
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException

/**
 * Pages stored N to a Brotli block (ADFA-6683 prototype), read through [DocumentationContentSource]
 * with the real decoder: `:common`'s tests have no brotli4j native (see common/build.gradle.kts).
 * The slice and the cache's own rules are covered there, by ContentBlocksTest.
 */
class ContentBlockSourceTest {
	@get:Rule
	val folder = TemporaryFolder()

	private lateinit var installedFile: File
	private lateinit var debugFile: File

	@Before
	fun setUp() {
		installedFile = folder.newFile("documentation.db")
		debugFile = File(folder.root, "debug-documentation.db")
		mockkStatic(SQLiteDatabase::class)
	}

	@After
	fun tearDown() {
		unmockkAll()
	}

	@Test
	fun `a blocked templated page renders through its template from its slice`() {
		opens(database(blocks = mapOf(1L to listOf(page("a/one.html", "One"), page("a/two.html", "Two")))))

		val content = found("a/two.html")

		assertEquals("<h1>Two</h1>", content.bytes.toString(Charsets.UTF_8))
		assertEquals("text/html", content.mimeType)
	}

	// Non-ASCII first, so the next item's offset counts UTF-8 bytes, not characters.
	@Test
	fun `a blocked html page is served as it is`() {
		opens(database(blocks = mapOf(2L to listOf(Item("j/x.html", NON_ASCII), Item("j/y.html", "<p>Y</p>")))))

		assertEquals("<p>Y</p>", found("j/y.html").bytes.toString(Charsets.UTF_8))
		assertEquals(NON_ASCII, found("j/x.html").bytes.toString(Charsets.UTF_8))
	}

	@Test
	fun `a row with no block in a blocked database decodes its own content`() {
		opens(database(blocks = mapOf(1L to listOf(page("a/one.html", "One"))), unblocked = mapOf("i/topic.html" to "<p>Topic</p>")))

		assertEquals("<p>Topic</p>", found("i/topic.html").bytes.toString(Charsets.UTF_8))
	}

	@Test
	fun `pages in one block decode it once`() {
		val db = database(blocks = mapOf(1L to listOf(page("a/one.html", "One"), page("a/two.html", "Two"))))
		opens(db)
		val source = source()

		assertEquals("<h1>One</h1>", found("a/one.html", source).bytes.toString(Charsets.UTF_8))
		assertEquals("<h1>Two</h1>", found("a/two.html", source).bytes.toString(Charsets.UTF_8))
		assertEquals("<h1>One</h1>", found("a/one.html", source).bytes.toString(Charsets.UTF_8))

		verify(exactly = 1) { db.rawQuery(match { it.contains("FROM ContentBlocks") }, any()) }
	}

	@Test
	fun `the block table is looked for once per database, not per request`() {
		val db = database(blocks = mapOf(1L to listOf(page("a/one.html", "One"), page("a/two.html", "Two"))))
		opens(db)
		val source = source()

		repeat(3) { found("a/one.html", source) }
		found("a/two.html", source)

		verify(exactly = 1) { db.rawQuery(match { it.contains("name = 'ContentBlockItems'") }, null) }
	}

	@Test
	fun `a block compressed against the database's dictionary decodes with it`() {
		val dictionary = "$SHARED interface companion object ".repeat(400)
		val html = "<p>$SHARED</p>".repeat(3)
		opens(database(blocks = mapOf(1L to listOf(Item("j/x.html", html))), dictionary = dictionary))

		assertEquals(html, found("j/x.html").bytes.toString(Charsets.UTF_8))
	}

	@Test
	fun `a jstopics file renders from a blocked page`() {
		val topic = Item("i/debug-enable.html", """{"hmTitle": "Enable the debugger"}""", templateId = HM_TOPIC_ID)
		opens(database(blocks = mapOf(1L to listOf(page("i/other.html", "Other"), topic))))

		val content = found("i/jstopics/debug-enable.js")

		assertEquals("""hmLoadTopic({hmTitle:"Enable the debugger"});""", content.bytes.toString(Charsets.UTF_8))
	}

	@Test
	fun `a database switch drops the decoded blocks`() {
		opens(database(blocks = mapOf(1L to listOf(page("a/one.html", "Old")))), installedFile)
		opens(database(blocks = mapOf(1L to listOf(page("a/one.html", "New")))), debugFile)
		val source = source()
		assertEquals("<h1>Old</h1>", found("a/one.html", source).bytes.toString(Charsets.UTF_8))

		swapToDebugDatabase()

		assertEquals("<h1>New</h1>", found("a/one.html", source).bytes.toString(Charsets.UTF_8))
	}

	@Test
	fun `a database switch asks the new database for its block table`() {
		opens(database(blocks = mapOf(1L to listOf(page("a/one.html", "Old")))), installedFile)
		val plain = database(unblocked = mapOf("a/one.html" to "<p>Plain</p>"), hasBlockTable = false)
		opens(plain, debugFile)
		val source = source()
		found("a/one.html", source)

		swapToDebugDatabase()

		assertEquals("<p>Plain</p>", found("a/one.html", source).bytes.toString(Charsets.UTF_8))
		verify(exactly = 1) { plain.rawQuery(match { it.contains("name = 'ContentBlockItems'") }, null) }
	}

	@Test
	fun `a database without ContentBlockItems never reads a block`() {
		val db = database(unblocked = mapOf("a/one.html" to "<p>Plain</p>"), hasBlockTable = false)
		opens(db)

		assertEquals("<p>Plain</p>", found("a/one.html").bytes.toString(Charsets.UTF_8))
		verify(exactly = 0) { db.rawQuery(match { it.contains("ContentBlockItems CBI") }, any()) }
		verify(exactly = 0) { db.rawQuery(match { it.contains("FROM ContentBlocks") }, any()) }
	}

	@Test
	fun `an item outside its block fails the lookup`() {
		opens(database(blocks = mapOf(1L to listOf(Item("j/x.html", "<p>X</p>", length = 99)))))

		val lookup = source().lookup("j/x.html")

		val failed = lookup as? DocumentationLookup.Failed ?: throw AssertionError("expected Failed, got $lookup")
		assertTrue(failed.cause.toString(), failed.cause is IllegalStateException)
	}

	@Test
	fun `a decode is logged at info with its sizes and time, and a hit at debug`() {
		opens(database(blocks = mapOf(4L to listOf(Item("j/x.html", "<p>X</p>"), Item("j/y.html", "<p>Y</p>")))))
		val lines = mutableListOf<Pair<Level, String>>()
		val sink = IdeLogRouter.ExternalSink { level, name, message, _ -> if (name == "DocBlocks") lines += level to message }
		IdeLogRouter.addSink(sink)
		try {
			val source = source()
			found("j/x.html", source)
			found("j/y.html", source)
		} finally {
			IdeLogRouter.removeSink(sink)
		}

		assertEquals(lines.toString(), 2, lines.size)
		val (decodeLevel, decode) = lines[0]
		assertEquals(Level.INFO, decodeLevel)
		val compressed = codec(null).compress("<p>X</p><p>Y</p>".toByteArray()).size
		assertTrue(decode, decode.startsWith("decode block=4 path=j/x.html compressed=$compressed plain=16 decodeMs="))
		assertTrue(decode, Regex("""decodeMs=\d+\.\d{3} cachePlain=16$""").containsMatchIn(decode))
		assertEquals(Level.DEBUG to "hit block=4 path=j/y.html plain=16", lines[1])
	}

	private class Item(
		val path: String,
		val text: String,
		val templateId: Int = 0,
		// Overrides the item's stored length, for a row that points outside its block.
		val length: Long? = null,
	)

	private fun page(
		path: String,
		title: String,
	) = Item(path, """{"title": "$title"}""", templateId = PAGE_ID)

	private fun source() = DocumentationContentSource(installedFile, debugFile, 0)

	private fun found(
		path: String,
		source: DocumentationContentSource = source(),
	): DocumentationContent {
		val lookup = source.lookupRequestPath(path).lookup
		return (lookup as? DocumentationLookup.Found)?.content ?: throw AssertionError("expected Found, got $lookup")
	}

	private fun opens(
		database: SQLiteDatabase,
		file: File? = null,
	) {
		if (file == null) {
			every { SQLiteDatabase.openDatabase(any(), isNull(), any()) } returns database
		} else {
			every { SQLiteDatabase.openDatabase(file.absolutePath, isNull(), any()) } returns database
		}
	}

	private fun swapToDebugDatabase() {
		debugFile.writeText("newer")
		debugFile.setLastModified(installedFile.lastModified() + 60_000)
	}

	private fun codec(dictionary: String?) = BrotliDictionaryCodec(dictionary?.let { toDirectByteBuffer(it.toByteArray()) })

	/**
	 * A database whose Content rows are [blocks]' items, each block stored as one Brotli row
	 * (against [dictionary] when given), and [unblocked]'s pages, each its own Brotli row. Template
	 * [PAGE_ID] renders a page's title; [HM_TOPIC_ID] is Help+Manual's topic template.
	 *
	 * Without [hasBlockTable] the content query has four columns, so reading a fifth throws, as a
	 * real cursor's does.
	 */
	private fun database(
		blocks: Map<Long, List<Item>> = emptyMap(),
		unblocked: Map<String, String> = emptyMap(),
		dictionary: String? = null,
		hasBlockTable: Boolean = true,
	): SQLiteDatabase {
		val codec = codec(dictionary)
		return mockk(relaxed = true) {
			every { rawQuery(match { it.contains("FROM   Content") }, any()) } answers { cursor(count = 0) }
			every { rawQuery(match { it.contains("name = 'ContentBlockItems'") }, null) } answers {
				cursor(count = if (hasBlockTable) 1 else 0)
			}

			for ((blockId, items) in blocks) {
				var offset = 0L
				for (item in items) {
					val itemOffset = offset
					val size =
						item.text
							.toByteArray()
							.size
							.toLong()
					val itemLength = item.length ?: size
					every { rawQuery(match { it.contains("FROM   Content") }, arrayOf(item.path)) } answers {
						contentCursor(ByteArray(0), item.templateId) {
							every { isNull(4) } returns false
							every { getLong(4) } returns blockId
							every { getLong(5) } returns itemOffset
							every { getLong(6) } returns itemLength
						}
					}
					offset += size
				}
				val data = codec.compress(items.joinToString("") { it.text }.toByteArray())
				if (dictionary != null) {
					// Proof the stream matched into the dictionary: without it, the block cannot decode.
					assertThrows(IOException::class.java) { codec(null).decompress(ByteArrayInputStream(data)) }
				}
				every { rawQuery(match { it.contains("FROM ContentBlocks") }, arrayOf(blockId.toString())) } answers {
					cursor { every { getBlob(0) } returns data }
				}
			}

			for ((path, text) in unblocked) {
				val data = codec.compress(text.toByteArray())
				every { rawQuery(match { it.contains("FROM   Content") }, arrayOf(path)) } answers {
					contentCursor(data, templateId = 0) {
						if (hasBlockTable) {
							every { isNull(4) } returns true
						} else {
							every { isNull(4) } throws IllegalStateException("Couldn't read row 0, col 4 from CursorWindow")
						}
					}
				}
			}

			every { rawQuery(match { it.contains("FROM Templates WHERE id = ?") }, arrayOf("$PAGE_ID")) } answers {
				cursor { every { getString(0) } returns "page.peb" }
			}
			every { rawQuery(match { it.contains("FROM Templates WHERE id = ?") }, arrayOf("$HM_TOPIC_ID")) } answers {
				cursor { every { getString(0) } returns "helpmanual-topic.peb" }
			}
			every { rawQuery(match { it.contains("WHERE name = ?") }, arrayOf("page.peb")) } answers {
				cursor { every { getBlob(0) } returns "<h1>{{ title }}</h1>".toByteArray() }
			}
			every { rawQuery(match { it.contains("WHERE name = ?") }, arrayOf("helpmanual-jstopic.peb")) } answers {
				// getInt answers the loader's COUNT(*) existence check, getBlob its read.
				cursor {
					every { getInt(0) } returns 1
					every { getBlob(0) } returns """hmLoadTopic({hmTitle:"{{ hmTitle }}"});""".toByteArray()
				}
			}

			if (dictionary != null) {
				// Version 3 declares the dictionary; its templates would be compressed too, so these
				// tests serve only untemplated pages from a database that has one.
				every {
					rawQuery(match { it.contains("FROM   sqlite_master") && it.contains("DocumentationDatabaseVersion") }, any())
				} answers { cursor() }
				every { rawQuery(match { it.contains("FROM   DocumentationDatabaseVersion") }, any()) } answers {
					cursor {
						every { isNull(0) } returns false
						every { getInt(0) } returns 3
						every { getInt(1) } returns 1
					}
				}
				every {
					rawQuery(match { it.contains("FROM sqlite_master") && it.contains("CompressionDictionary") }, null)
				} answers { cursor() }
				every { rawQuery(match { it.contains("SELECT data FROM CompressionDictionary") }, null) } answers {
					cursor { every { getBlob(0) } returns dictionary.toByteArray() }
				}
			}
		}
	}

	private fun contentCursor(
		bytes: ByteArray,
		templateId: Int,
		columns: Cursor.() -> Unit,
	) = cursor {
		every { getBlob(0) } returns bytes
		every { getString(1) } returns "text/html"
		every { getString(2) } returns "brotli"
		every { getInt(3) } returns templateId
		columns()
	}

	private fun cursor(
		count: Int = 1,
		columns: Cursor.() -> Unit = {},
	) = mockk<Cursor>(relaxed = true) {
		every { this@mockk.count } returns count
		every { moveToFirst() } returns (count > 0)
		columns()
	}

	private companion object {
		const val PAGE_ID = 7
		const val HM_TOPIC_ID = 5

		// Text the dictionary and the page share, so the page's block matches into the dictionary.
		const val SHARED = """<div class="api-item"><h3 class="api-name">docs-sidebar toc-element"""

		// A Y with diaeresis (2 bytes in UTF-8) and an ellipsis (3 bytes).
		const val NON_ASCII = "<p>Ÿ…</p>"
	}
}
