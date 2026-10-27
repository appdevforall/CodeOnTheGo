package com.itsaky.androidide.localWebServer

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import com.aayushatharva.brotli4j.Brotli4jLoader
import com.aayushatharva.brotli4j.encoder.Encoder
import com.itsaky.androidide.documentation.DocumentationContent
import com.itsaky.androidide.documentation.DocumentationContentSource
import com.itsaky.androidide.documentation.DocumentationLookup
import com.itsaky.androidide.documentation.TemplateRenderException
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
import org.junit.Assert.assertFalse
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Pages stored in content blocks (ADFA-6683, documentation version 4), read through
 * [DocumentationContentSource] with the real decoder: `:common`'s tests have no brotli4j native
 * (see common/build.gradle.kts). The slice and the cache's own rules are covered there, by
 * ContentBlocksTest, and the version gate's choice of query by DocumentationContentSourceTest.
 *
 * The database is a mock that tells the two content queries apart: the one joining
 * ContentBlockItems, which a version 4 database gets, and the original one.
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

	// Non-ASCII first, so the next item's offset counts UTF-8 bytes, not characters.
	@Test
	fun `an untemplated j page is served from its slice as it is`() {
		opens(database(blocks = mapOf(2L to listOf(Item("j/x.html", NON_ASCII), Item("j/y.html", "<p>Y</p>")))))

		assertEquals("<p>Y</p>", text(found("j/y.html")))
		assertEquals(NON_ASCII, text(found("j/x.html")))
		assertEquals("text/html", found("j/x.html").mimeType)
	}

	@Test
	fun `templated a and k pages render through their compressed template from their slices`() {
		opens(
			database(
				blocks =
					mapOf(
						1L to listOf(page("a/one.html", "One"), page("a/two.html", "Two")),
						2L to listOf(page("k/one.html", "Kotlin one"), page("k/two.html", "Kotlin two")),
					),
			),
		)
		val source = source()

		assertEquals("<h1>Two</h1>", text(found("a/two.html", source)))
		assertEquals("<h1>One</h1>", text(found("a/one.html", source)))
		assertEquals("<h1>Kotlin two</h1>", text(found("k/two.html", source)))
	}

	@Test
	fun `an i page and its jstopics file both render from the page's slice`() {
		val topic = Item("i/debug-enable.html", """{"hmTitle": "Enable the debugger"}""", templateId = HM_TOPIC_ID)
		opens(database(blocks = mapOf(1L to listOf(Item("i/other.html", "<p>Other</p>"), topic))))
		val source = source()

		assertEquals("<h1>Enable the debugger</h1>", text(found("i/debug-enable.html", source)))
		val jsTopic = found("i/jstopics/debug-enable.js", source)
		assertEquals("""hmLoadTopic({hmTitle:"Enable the debugger"});""", text(jsTopic))
		assertEquals("text/javascript", jsTopic.mimeType)
	}

	// The last two sets: every Brotli row under a/devsite/ and under i/, templated or not.
	@Test
	fun `untemplated a devsite and i rows are served from their slices`() {
		opens(
			database(
				blocks =
					mapOf(
						4L to listOf(Item("a/devsite/style.css", "body{}"), Item("a/devsite/page.html", "<p>Devsite</p>")),
						5L to listOf(Item("i/script.js", "var x;"), Item("i/plain.html", "<p>Plain</p>")),
					),
			),
		)
		val source = source()

		assertEquals("<p>Devsite</p>", text(found("a/devsite/page.html", source)))
		assertEquals("body{}", text(found("a/devsite/style.css", source)))
		assertEquals("<p>Plain</p>", text(found("i/plain.html", source)))
		assertEquals("var x;", text(found("i/script.js", source)))
	}

	@Test
	fun `a row with no item in a version 4 database decodes its own content`() {
		opens(database(blocks = mapOf(1L to listOf(page("a/one.html", "One"))), unblocked = mapOf("i/topic.html" to "<p>Topic</p>")))

		assertEquals("<p>Topic</p>", text(found("i/topic.html")))
	}

	@Test
	fun `pages in one block decode it once, and later loads are cache hits`() {
		val db = database(blocks = mapOf(1L to listOf(page("a/one.html", "One"), page("a/two.html", "Two"))))
		opens(db)
		val source = source()

		assertEquals("<h1>One</h1>", text(found("a/one.html", source)))
		assertEquals("<h1>Two</h1>", text(found("a/two.html", source)))
		assertEquals("<h1>One</h1>", text(found("a/one.html", source)))

		verify(exactly = 1) { db.rawQuery(match { it.contains("FROM ContentBlocks") }, any()) }
	}

	// OfflineDocumentationTools keeps a block under 4 MiB decoded, but nothing here relies on that.
	@Test
	fun `a block over the cache budget is still served, and decoded again on the next load`() {
		val filler = "x".repeat(9 * 1024 * 1024)
		val items = listOf(Item("j/filler.html", filler), Item("j/x.html", "<p>X</p>"))
		val db = database(blocks = mapOf(1L to items), blockData = mapOf(1L to fastBrotli(filler + "<p>X</p>")))
		opens(db)
		val source = source()

		assertEquals("<p>X</p>", text(found("j/x.html", source)))
		assertEquals("<p>X</p>", text(found("j/x.html", source)))

		verify(exactly = 2) { db.rawQuery(match { it.contains("FROM ContentBlocks") }, any()) }
	}

	@Test
	fun `a block compressed against the database's dictionary decodes with it`() {
		val dictionary = "$SHARED interface companion object ".repeat(400)
		val html = "<p>$SHARED</p>".repeat(3)
		opens(database(blocks = mapOf(1L to listOf(Item("j/x.html", html))), dictionary = dictionary))

		assertEquals(html, text(found("j/x.html")))
	}

	// A version 3 file whose rows hold their own content, as that version promises, and block tables
	// that hold other bytes for the same rows. The tables' presence changes nothing.
	@Test
	fun `a version 3 database reads every row's own content, even with the block tables present`() {
		val db =
			database(
				blocks = mapOf(1L to listOf(Item("j/x.html", "<p>Block</p>", rowText = "<p>Row</p>"))),
				declaredMajorVersion = 3,
			)
		opens(db)

		assertEquals("<p>Row</p>", text(found("j/x.html")))
		verify(exactly = 0) { db.rawQuery(match { it.contains("ContentBlockItems") }, any()) }
		verify(exactly = 0) { db.rawQuery(match { it.contains("FROM ContentBlocks") }, any()) }
	}

	// Not read as a database with nothing blocked, which would serve every blocked row's empty content.
	@Test
	fun `a version 4 database without the block tables fails the page with SQLite's error`() {
		opens(
			database(
				blocks = mapOf(1L to listOf(Item("j/x.html", "<p>X</p>"))),
				unblocked = mapOf("j/y.html" to "<p>Y</p>"),
				hasBlockTables = false,
			),
		)
		val source = source()

		for (path in listOf("j/x.html", "j/y.html")) {
			val cause = failure(path, source)
			assertTrue(cause.toString(), cause is SQLiteException)
		}
	}

	@Test
	fun `a block that does not decode fails the page, naming the block and the path`() {
		// Compressed against a dictionary the database does not have, so it cannot decode.
		val data = codec("$SHARED interface companion object ".repeat(400)).compress("<p>$SHARED</p>".repeat(3).toByteArray())
		opens(database(blocks = mapOf(3L to listOf(Item("j/x.html", "<p>X</p>"))), blockData = mapOf(3L to data)))

		val cause = failure("j/x.html")

		assertTrue(cause.toString(), cause is IOException)
		assertEquals("Content block 3 does not decode, for path 'j/x.html'", cause.message)
	}

	@Test
	fun `a missing block fails the page`() {
		opens(database(blocks = mapOf(3L to listOf(Item("j/x.html", "<p>X</p>"))), missingBlocks = setOf(3L)))

		val cause = failure("j/x.html")

		assertTrue(cause.toString(), cause is IllegalStateException)
		assertEquals("Content block 3 not found, for path 'j/x.html'", cause.message)
	}

	@Test
	fun `an item running past the end of its block fails the page`() {
		opens(database(blocks = mapOf(1L to listOf(Item("j/x.html", "<p>X</p>", length = 99)))))

		val cause = failure("j/x.html")

		assertTrue(cause.toString(), cause is IllegalStateException)
	}

	// Narrowed to Int unchecked, 2^32 would wrap to 0 and serve the first item's bytes instead.
	// Read as 0, a NULL length would slice an empty page out of the block.
	@Test
	fun `an item with no length fails the page rather than serving an empty one`() {
		opens(database(blocks = mapOf(1L to listOf(Item("j/x.html", "<p>X</p>", nullLength = true)))))

		val cause = failure("j/x.html")

		assertTrue(cause.toString(), cause is IllegalStateException)
		assertEquals("ContentBlockItems row for 'j/x.html' in block 1 has no offset or length", cause.message)
	}

	@Test
	fun `an item past Int range fails the page rather than serving another item's bytes`() {
		val items = listOf(Item("j/x.html", "<p>X</p>"), Item("j/y.html", "<p>Y</p>", offset = 1L shl 32))
		opens(database(blocks = mapOf(1L to items)))

		val cause = failure("j/y.html")

		assertTrue(cause.toString(), cause is IllegalStateException)
	}

	// A blocked row whose item is gone: its own content is empty. Never an empty 200.
	@Test
	fun `an empty Brotli row with no item in a version 4 database fails rather than serving an empty page`() {
		opens(database(unblocked = mapOf("j/x.html" to ""), emptyRows = setOf("j/x.html")))

		val cause = failure("j/x.html")

		assertTrue(cause.toString(), cause is IllegalStateException)
		assertEquals("Content row 'j/x.html' is empty and has no ContentBlockItems row", cause.message)
	}

	@Test
	fun `a swap between version 4 files with the same block ids serves the new blocks`() {
		opens(database(blocks = mapOf(1L to listOf(page("a/one.html", "Old")))), installedFile)
		opens(database(blocks = mapOf(1L to listOf(page("a/one.html", "New")))), debugFile)
		val source = source()
		assertEquals("<h1>Old</h1>", text(found("a/one.html", source)))

		swapToDebugDatabase()

		assertEquals("<h1>New</h1>", text(found("a/one.html", source)))
	}

	@Test
	fun `a swap from a version 3 file to a version 4 file reads the new one's blocks`() {
		opens(database(unblocked = mapOf("j/x.html" to "<p>Old</p>"), declaredMajorVersion = 3, hasBlockTables = false), installedFile)
		opens(database(blocks = mapOf(1L to listOf(Item("j/x.html", "<p>New</p>")))), debugFile)
		val source = source()
		assertEquals("<p>Old</p>", text(found("j/x.html", source)))

		swapToDebugDatabase()

		assertEquals("<p>New</p>", text(found("j/x.html", source)))
	}

	@Test
	fun `a swap from a version 4 file to a version 3 file reads the new one's rows`() {
		opens(database(blocks = mapOf(1L to listOf(Item("j/x.html", "<p>Old</p>")))), installedFile)
		opens(database(unblocked = mapOf("j/x.html" to "<p>New</p>"), declaredMajorVersion = 3, hasBlockTables = false), debugFile)
		val source = source()
		assertEquals("<p>Old</p>", text(found("j/x.html", source)))

		swapToDebugDatabase()

		assertEquals("<p>New</p>", text(found("j/x.html", source)))
	}

	// The installers rewrite the installed file in place; the reopen drops the old file's blocks too.
	@Test
	fun `a rewritten installed file is reopened with none of the old file's blocks`() {
		val before = database(blocks = mapOf(1L to listOf(Item("j/x.html", "<p>Before</p>"))))
		val after = database(blocks = mapOf(1L to listOf(Item("j/x.html", "<p>After</p>"))))
		every { SQLiteDatabase.openDatabase(installedFile.absolutePath, isNull(), any()) } returnsMany listOf(before, after)
		val source = source()
		assertEquals("<p>Before</p>", text(found("j/x.html", source)))

		installedFile.writeText("rewritten")
		installedFile.setLastModified(installedFile.lastModified() + 60_000)

		assertEquals("<p>After</p>", text(found("j/x.html", source)))
	}

	// The read holds the read lock from its query to the block's cache insert, and the swap waits on
	// the write lock, so the old database's block cannot be cached after the swap clears the cache.
	@Test
	fun `a swap waits for a block being decoded, so the old block is not cached after it`() {
		val decoding = CountDownLatch(1)
		val release = CountDownLatch(1)
		val onOldBlockRead = {
			decoding.countDown()
			check(release.await(10, TimeUnit.SECONDS)) { "never released" }
		}
		opens(database(blocks = mapOf(1L to listOf(Item("j/x.html", "<p>Old</p>"))), onBlockRead = onOldBlockRead), installedFile)
		opens(database(blocks = mapOf(1L to listOf(Item("j/x.html", "<p>New</p>")))), debugFile)
		val source = source()
		val pool = Executors.newSingleThreadExecutor()
		try {
			val first = pool.submit<String> { text(found("j/x.html", source)) }
			assertTrue(decoding.await(10, TimeUnit.SECONDS))
			swapToDebugDatabase()

			var second: String? = null
			val reader = thread { second = text(found("j/x.html", source)) }
			// Until it is parked on the swap's write lock; without the lock, until it is done.
			val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
			while (reader.isAlive && reader.state != Thread.State.WAITING && System.nanoTime() < deadline) Thread.sleep(1)
			release.countDown()

			assertEquals("<p>Old</p>", first.get(10, TimeUnit.SECONDS))
			reader.join(10_000)
			assertEquals("<p>New</p>", second)
			assertEquals("<p>New</p>", text(found("j/x.html", source)))
		} finally {
			release.countDown()
			pool.shutdownNow()
		}
	}

	@Test
	fun `a decode is logged at debug with its sizes and time, and so is a hit`() {
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
		assertEquals(Level.DEBUG, decodeLevel)
		val compressed = codec(null).compress("<p>X</p><p>Y</p>".toByteArray()).size
		assertTrue(decode, decode.startsWith("decode block=4 path=j/x.html compressed=$compressed plain=16 decodeMs="))
		assertTrue(decode, Regex("""decodeMs=\d+\.\d{3} cachePlain=16$""").containsMatchIn(decode))
		assertEquals(Level.DEBUG to "hit block=4 path=j/y.html plain=16", lines[1])
	}

	private class Item(
		val path: String,
		val text: String,
		val templateId: Int = 0,
		// Override the item's stored offset or length, for a row that points outside its block.
		val offset: Long? = null,
		val length: Long? = null,
		// A NULL length, which the schema rules out and a debug database could still hold.
		val nullLength: Boolean = false,
		// The row's own content, Brotli-compressed; by default empty, as a blocked row's is.
		val rowText: String? = null,
	)

	private fun page(
		path: String,
		title: String,
	) = Item(path, """{"title": "$title"}""", templateId = PAGE_ID)

	private fun source() = DocumentationContentSource(installedFile, debugFile, 0)

	private fun text(content: DocumentationContent) = content.bytes.toString(Charsets.UTF_8)

	private fun found(
		path: String,
		source: DocumentationContentSource = source(),
	): DocumentationContent {
		val lookup = source.lookupRequestPath(path).lookup
		return (lookup as? DocumentationLookup.Found)?.content ?: throw AssertionError("expected Found, got $lookup")
	}

	/**
	 * Why [path] failed. Never a [TemplateRenderException], the one failure whose message the
	 * transports send: the others can carry SQL text or a file path, and are answered without it.
	 */
	private fun failure(
		path: String,
		source: DocumentationContentSource = source(),
	): Exception {
		val lookup = source.lookupRequestPath(path).lookup
		val cause = (lookup as? DocumentationLookup.Failed)?.cause ?: throw AssertionError("expected Failed, got $lookup")
		assertFalse(cause.toString(), cause is TemplateRenderException)
		return cause
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

	// Quality 1, for a block too big to compress at the pipeline's 11 in reasonable time.
	private fun fastBrotli(text: String): ByteArray {
		Brotli4jLoader.ensureAvailability()
		return Encoder.compress(text.toByteArray(), Encoder.Parameters().setQuality(1))
	}

	/**
	 * A database declaring [declaredMajorVersion]. [blocks]' items are rows with empty content (or
	 * their [Item.rowText]) and an item in their block; [unblocked]'s pages are rows holding their own
	 * Brotli content, empty for [emptyRows]. Each block is stored as one Brotli row, against
	 * [dictionary] when given, unless [blockData] gives its bytes; a block in [missingBlocks] has no
	 * row, and [onBlockRead] runs as a block is read. From version 3 the templates are compressed
	 * too. Template [PAGE_ID] renders a page's title; [HM_TOPIC_ID] is Help+Manual's topic template.
	 *
	 * The query joining ContentBlockItems throws SQLite's error without [hasBlockTables], as it would
	 * on a file without the tables. The original query reads a blocked row's own content, which is
	 * empty unless its item says otherwise, and has four columns: reading a fifth throws, as a real
	 * cursor's does.
	 */
	private fun database(
		blocks: Map<Long, List<Item>> = emptyMap(),
		unblocked: Map<String, String> = emptyMap(),
		emptyRows: Set<String> = emptySet(),
		dictionary: String? = null,
		declaredMajorVersion: Int? = 4,
		hasBlockTables: Boolean = true,
		blockData: Map<Long, ByteArray> = emptyMap(),
		missingBlocks: Set<Long> = emptySet(),
		onBlockRead: () -> Unit = {},
	): SQLiteDatabase {
		val codec = codec(dictionary)
		val blockedQuery: (String) -> Boolean = { it.contains("LEFT JOIN ContentBlockItems") }
		val plainQuery: (String) -> Boolean = { it.contains("FROM   Content") && !it.contains("ContentBlockItems") }
		return mockk(relaxed = true) {
			every { rawQuery(match { it.contains("FROM   Content") }, any()) } answers { cursor(count = 0) }
			if (!hasBlockTables) {
				every { rawQuery(match(blockedQuery), any()) } throws
					SQLiteException("no such table: ContentBlockItems (code 1 SQLITE_ERROR)")
			}

			for ((blockId, items) in blocks) {
				var offset = 0L
				for (item in items) {
					val itemOffset = item.offset ?: offset
					val size =
						item.text
							.toByteArray()
							.size
							.toLong()
					val itemLength = item.length ?: size
					val rowContent = item.rowText?.let { codec.compress(it.toByteArray()) } ?: ByteArray(0)
					if (hasBlockTables) {
						every { rawQuery(match(blockedQuery), arrayOf(item.path)) } answers {
							contentCursor(rowContent, item.templateId) {
								every { isNull(4) } returns false
								every { getLong(4) } returns blockId
								every { getLong(5) } returns itemOffset
								every { isNull(6) } returns item.nullLength
								every { getLong(6) } returns if (item.nullLength) 0 else itemLength
							}
						}
					}
					every { rawQuery(match(plainQuery), arrayOf(item.path)) } answers {
						contentCursor(rowContent, item.templateId) { fourColumns() }
					}
					offset += size
				}
				val data = blockData[blockId] ?: codec.compress(items.joinToString("") { it.text }.toByteArray())
				if (dictionary != null && blockId !in blockData) {
					// Proof the stream matched into the dictionary: without it, the block cannot decode.
					assertThrows(IOException::class.java) { codec(null).decompress(ByteArrayInputStream(data)) }
				}
				every { rawQuery(match { it.contains("FROM ContentBlocks") }, arrayOf(blockId.toString())) } answers {
					onBlockRead()
					if (blockId in missingBlocks) cursor(count = 0) else cursor { every { getBlob(0) } returns data }
				}
			}

			for ((path, text) in unblocked) {
				val data = if (path in emptyRows) ByteArray(0) else codec.compress(text.toByteArray())
				if (hasBlockTables) {
					every { rawQuery(match(blockedQuery), arrayOf(path)) } answers {
						contentCursor(data, templateId = 0) { every { isNull(4) } returns true }
					}
				}
				every { rawQuery(match(plainQuery), arrayOf(path)) } answers {
					contentCursor(data, templateId = 0) { fourColumns() }
				}
			}

			val compressTemplates = declaredMajorVersion != null && declaredMajorVersion >= 3

			fun template(body: String) = if (compressTemplates) codec.compress(body.toByteArray()) else body.toByteArray()
			every { rawQuery(match { it.contains("FROM Templates WHERE id = ?") }, arrayOf("$PAGE_ID")) } answers {
				cursor { every { getString(0) } returns "page.peb" }
			}
			every { rawQuery(match { it.contains("FROM Templates WHERE id = ?") }, arrayOf("$HM_TOPIC_ID")) } answers {
				cursor { every { getString(0) } returns "helpmanual-topic.peb" }
			}
			every { rawQuery(match { it.contains("WHERE name = ?") }, arrayOf("page.peb")) } answers {
				cursor { every { getBlob(0) } returns template("<h1>{{ title }}</h1>") }
			}
			every { rawQuery(match { it.contains("WHERE name = ?") }, arrayOf("helpmanual-topic.peb")) } answers {
				cursor { every { getBlob(0) } returns template("<h1>{{ hmTitle }}</h1>") }
			}
			every { rawQuery(match { it.contains("WHERE name = ?") }, arrayOf("helpmanual-jstopic.peb")) } answers {
				// getInt answers the loader's COUNT(*) existence check, getBlob its read.
				cursor {
					every { getInt(0) } returns 1
					every { getBlob(0) } returns template("""hmLoadTopic({hmTitle:"{{ hmTitle }}"});""")
				}
			}

			every {
				rawQuery(match { it.contains("FROM   sqlite_master") && it.contains("DocumentationDatabaseVersion") }, any())
			} answers { cursor(count = if (declaredMajorVersion == null) 0 else 1) }
			if (declaredMajorVersion != null) {
				every { rawQuery(match { it.contains("FROM   DocumentationDatabaseVersion") }, any()) } answers {
					cursor {
						every { isNull(0) } returns false
						every { getInt(0) } returns declaredMajorVersion
						every { getInt(1) } returns 1
					}
				}
			}
			if (dictionary != null) {
				every {
					rawQuery(match { it.contains("FROM sqlite_master") && it.contains("CompressionDictionary") }, null)
				} answers { cursor() }
				every { rawQuery(match { it.contains("SELECT data FROM CompressionDictionary") }, null) } answers {
					cursor { every { getBlob(0) } returns dictionary.toByteArray() }
				}
			}
		}
	}

	private fun Cursor.fourColumns() {
		// The receiver is explicit because MockK's matcher scope has an isNull() of its own.
		every { this@fourColumns.isNull(any()) } throws IllegalStateException("Couldn't read row 0, col 4 from CursorWindow")
		every { getLong(any()) } throws IllegalStateException("Couldn't read row 0, col 4 from CursorWindow")
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
