package com.itsaky.androidide.documentation

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.utils.BrotliDictionaryCodec
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * Covers the pipeline both documentation transports read through (ADFA-5176): what a lookup reports,
 * how chunked rows are reassembled, and what a debug-database swap does to the handle and to the
 * generation counter these tests read a swap off.
 *
 * The database itself is a mock. These tests are about this class's decisions, and a real
 * SQLiteDatabase needs a device; the on-device behavior is covered by WebServerTest and by the
 * brotli decode tests in the app module.
 */
class DocumentationContentSourceTest {
	@get:Rule
	val folder = TemporaryFolder()

	private lateinit var installedFile: File
	private lateinit var debugFile: File

	@Before
	fun setUp() {
		installedFile = folder.newFile("documentation.db")
		// Not created: a debug database that does not exist is the normal case.
		debugFile = File(folder.root, "debug-documentation.db")

		mockkStatic(SQLiteDatabase::class)
	}

	@After
	fun tearDown() {
		unmockkAll()
	}

	// The template rows these tests store are plain text, and :common's tests have no brotli4j native
	// to compress them with, so the decode is the identity unless a test says otherwise. The Brotli
	// decode itself is covered in :app, by CompressedTemplateTest.
	private fun source(
		debugCheckIntervalMs: Long = 1_000,
		decode: (BrotliDictionaryCodec, ByteArray) -> ByteArray = { _, body -> body },
	) = DocumentationContentSource(installedFile, debugFile, debugCheckIntervalMs, decode)

	private val reversing: (BrotliDictionaryCodec, ByteArray) -> ByteArray = { _, body -> body.reversedArray() }

	// For non-ASCII text, whose UTF-8 bytes reversed are not UTF-8: reverses the characters instead.
	private val reversingChars: (BrotliDictionaryCodec, ByteArray) -> ByteArray =
		{ _, body -> body.toString(Charsets.UTF_8).reversed().toByteArray(Charsets.UTF_8) }

	/** A Content row as the source's query sees it. */
	private fun contentCursor(
		bytes: ByteArray = "hello".toByteArray(),
		mimeType: String = "text/plain",
		compression: String = "none",
		templateId: Int = 0,
		rowCount: Int = 1,
	) = mockk<Cursor>(relaxed = true) {
		every { count } returns rowCount
		every { moveToFirst() } returns (rowCount > 0)
		every { getBlob(0) } returns bytes
		every { getString(1) } returns mimeType
		every { getString(2) } returns compression
		every { getInt(3) } returns templateId
	}

	private fun database(contentCursor: Cursor): SQLiteDatabase =
		mockk<SQLiteDatabase>(relaxed = true) {
			every { rawQuery(match { it.contains("FROM   Content") }, any()) } returns contentCursor
		}

	/** A database that declares [major] in ADFA-5220's version table, or none when null. */
	private fun database(
		contentCursor: Cursor,
		declaredMajorVersion: Int?,
		dictionary: ByteArray? = "test-dictionary".toByteArray(),
	): SQLiteDatabase =
		mockk<SQLiteDatabase>(relaxed = true) {
			every { rawQuery(match { it.contains("FROM   Content") }, any()) } returns contentCursor
			declaresMajorVersion(declaredMajorVersion)
			every {
				rawQuery(match { it.contains("FROM sqlite_master") && it.contains("CompressionDictionary") }, null)
			} returns mockk<Cursor>(relaxed = true) { every { moveToFirst() } returns (dictionary != null) }
			every { rawQuery(match { it.contains("SELECT data FROM CompressionDictionary") }, null) } returns
				mockk<Cursor>(relaxed = true) {
					every { moveToFirst() } returns (dictionary != null)
					every { getBlob(0) } returns dictionary
				}
		}

	/** Stubs ADFA-5220's version table on this mock: declaring [major], or no table when null. */
	private fun SQLiteDatabase.declaresMajorVersion(major: Int?) {
		every {
			rawQuery(match { it.contains("FROM   sqlite_master") && it.contains("DocumentationDatabaseVersion") }, any())
		} returns mockk<Cursor>(relaxed = true) { every { moveToFirst() } returns (major != null) }
		if (major != null) {
			every { rawQuery(match { it.contains("FROM   DocumentationDatabaseVersion") }, any()) } returns
				mockk<Cursor>(relaxed = true) {
					every { moveToFirst() } returns true
					every { isNull(0) } returns false
					every { getInt(0) } returns major
				}
		}
	}

	// ADFA-5220: the dictionary is gated on the version the database declares, not on whether a
	// CompressionDictionary table happens to exist. These three cover the gate that WebServerTest
	// used to own before the decode moved into this class.
	@Test
	fun `a database declaring a version below 2 is never asked for a dictionary`() {
		assertDictionaryRead(declaredMajorVersion = 1, expected = 0)
	}

	@Test
	fun `a database with no version table is never asked for a dictionary`() {
		assertDictionaryRead(declaredMajorVersion = null, expected = 0)
	}

	// The gate is a floor, not a match: a later format still carries the dictionary.
	@Test
	fun `a database declaring a version above 2 still loads the dictionary`() {
		assertDictionaryRead(declaredMajorVersion = 3, expected = 1)
	}

	/**
	 * The dictionary cursors are stubbed as *available* in every case, including the ones expecting
	 * zero reads: that is what makes this a test of the gate rather than of a missing table.
	 */
	private fun assertDictionaryRead(
		declaredMajorVersion: Int?,
		expected: Int,
	) {
		val database = database(contentCursor(compression = "brotli"), declaredMajorVersion)
		every { SQLiteDatabase.openDatabase(any(), isNull(), any()) } returns database

		source().use { it.lookup("a/page.html") }

		verify(exactly = expected) {
			database.rawQuery(match { it.contains("SELECT data FROM CompressionDictionary") }, null)
		}
		// The version itself is always consulted -- that is the gate being reached at all.
		verify(atLeast = 1) {
			database.rawQuery(match { it.contains("FROM   sqlite_master") && it.contains("DocumentationDatabaseVersion") }, any())
		}
	}

	@Test
	fun `lookup returns the row for a path`() {
		val database = database(contentCursor(bytes = "page".toByteArray(), mimeType = "text/html"))
		every { SQLiteDatabase.openDatabase(any(), isNull(), any()) } returns database

		val lookup = source().lookup("i/index.html")

		assertThat(lookup).isInstanceOf(DocumentationLookup.Found::class.java)
		val content = (lookup as DocumentationLookup.Found).content
		assertThat(content.bytes.toString(Charsets.UTF_8)).isEqualTo("page")
		assertThat(content.mimeType).isEqualTo("text/html")
	}

	@Test
	fun `lookup reports an unknown path as not found`() {
		every { SQLiteDatabase.openDatabase(any(), isNull(), any()) } returns database(contentCursor(rowCount = 0))

		assertThat(source().lookup("nope")).isEqualTo(DocumentationLookup.NotFound)
	}

	@Test
	fun `lookup reports duplicate rows as ambiguous, since the path is meant to be unique`() {
		every { SQLiteDatabase.openDatabase(any(), isNull(), any()) } returns database(contentCursor(rowCount = 2))

		val lookup = source().lookup("i/index.html")

		assertThat(lookup).isEqualTo(DocumentationLookup.Ambiguous(2))
	}

	@Test
	fun `lookup reports a read that throws rather than propagating it`() {
		val database =
			mockk<SQLiteDatabase>(relaxed = true) {
				every { rawQuery(match { it.contains("FROM   Content") }, any()) } throws IllegalStateException("boom")
			}
		every { SQLiteDatabase.openDatabase(any(), isNull(), any()) } returns database

		val lookup = source().lookup("i/index.html")

		assertThat(lookup).isInstanceOf(DocumentationLookup.Failed::class.java)
		assertThat((lookup as DocumentationLookup.Failed).cause).hasMessageThat().isEqualTo("boom")
	}

	// Stored Content.path rows in the shipped database are percent-encoded (e.g.
	// `t/Draft%20%20Tutorial.html`, whose own markup references its siblings the same way), so the
	// raw request target is what matches and must be queried verbatim, not decoded first.
	@Test
	fun `a percent-encoded request target is queried verbatim and found`() {
		val database = database(contentCursor(bytes = "page".toByteArray()))
		every { SQLiteDatabase.openDatabase(any(), isNull(), any()) } returns database

		val result = source().lookupRequestPath("t/Draft%20%20Tutorial.html")

		assertThat(result.queriedPath).isEqualTo("t/Draft%20%20Tutorial.html")
		assertThat(result.lookup).isInstanceOf(DocumentationLookup.Found::class.java)
		verify(exactly = 1) {
			database.rawQuery(match { it.contains("FROM   Content") }, arrayOf("t/Draft%20%20Tutorial.html"))
		}
	}

	// A database ingested with decoded paths keeps working: the decoded form is the fallback.
	@Test
	fun `the decoded form is tried when the raw target misses`() {
		val database =
			mockk<SQLiteDatabase>(relaxed = true) {
				every { rawQuery(match { it.contains("FROM   Content") }, any()) } answers {
					if (secondArg<Array<String>>()[0] == "a/my file.html") {
						contentCursor(bytes = "page".toByteArray())
					} else {
						contentCursor(rowCount = 0)
					}
				}
			}
		every { SQLiteDatabase.openDatabase(any(), isNull(), any()) } returns database

		val result = source().lookupRequestPath("a/my%20file.html")

		assertThat(result.queriedPath).isEqualTo("a/my file.html")
		assertThat(result.lookup).isInstanceOf(DocumentationLookup.Found::class.java)
	}

	// URLDecoder turns "+" into a space; a stored path containing a literal plus must survive --
	// and since the protected decode is then the identity, there is no second query to make.
	@Test
	fun `a plus stays a plus, and an unchanged decode is not queried twice`() {
		val database = database(contentCursor(rowCount = 0))
		every { SQLiteDatabase.openDatabase(any(), isNull(), any()) } returns database

		val result = source().lookupRequestPath("a/c++.html")

		assertThat(result.queriedPath).isEqualTo("a/c++.html")
		assertThat(result.lookup).isEqualTo(DocumentationLookup.NotFound)
		verify(exactly = 1) { database.rawQuery(match { it.contains("FROM   Content") }, any()) }
	}

	// A malformed escape is not a reason to fail the request: the verbatim lookup already ran, and
	// there is no decoded form left to try.
	@Test
	fun `a malformed escape is looked up verbatim rather than failing the request`() {
		val database = database(contentCursor(rowCount = 0))
		every { SQLiteDatabase.openDatabase(any(), isNull(), any()) } returns database

		val result = source().lookupRequestPath("a/%zz.html")

		assertThat(result.queriedPath).isEqualTo("a/%zz.html")
		assertThat(result.lookup).isEqualTo(DocumentationLookup.NotFound)
		verify(exactly = 1) { database.rawQuery(match { it.contains("FROM   Content") }, any()) }
	}

	@Test
	fun `lookup says not found when the database cannot be opened at all`() {
		every { SQLiteDatabase.openDatabase(any(), isNull(), any()) } throws IllegalStateException("cannot open")

		assertThat(source().lookup("i/index.html")).isEqualTo(DocumentationLookup.NotFound)
	}

	@Test
	fun `content split across rows is reassembled in order`() {
		val first = ByteArray(DocumentationContentSource.CONTENT_CHUNK_SIZE) { 'a'.code.toByte() }
		val second = ByteArray(DocumentationContentSource.CONTENT_CHUNK_SIZE) { 'b'.code.toByte() }
		val third = "tail".toByteArray()

		val chunkCursors =
			listOf(second, third).map { chunk ->
				mockk<Cursor>(relaxed = true) {
					every { moveToFirst() } returns true
					every { getBlob(0) } returns chunk
				}
			}
		val database =
			mockk<SQLiteDatabase>(relaxed = true) {
				every { rawQuery(match { it.contains("FROM   Content") }, any()) } returns contentCursor(bytes = first)
				every { rawQuery(match { it.startsWith("SELECT content FROM Content") }, arrayOf("big-1")) } returns chunkCursors[0]
				every { rawQuery(match { it.startsWith("SELECT content FROM Content") }, arrayOf("big-2")) } returns chunkCursors[1]
			}
		every { SQLiteDatabase.openDatabase(any(), isNull(), any()) } returns database

		val lookup = source().lookup("big")

		val bytes = (lookup as DocumentationLookup.Found).content.bytes
		assertThat(bytes.size).isEqualTo(first.size + second.size + third.size)
		assertThat(bytes.copyOfRange(bytes.size - third.size, bytes.size).toString(Charsets.UTF_8)).isEqualTo("tail")
	}

	@Test
	fun `a templated row comes back rendered, so no caller needs the template engine`() {
		val database = templatedDatabase("page.peb" to "Hello {{ who }}!")
		every { SQLiteDatabase.openDatabase(any(), isNull(), any()) } returns database

		val lookup = source().lookup("k/html/basic-syntax.html")

		assertThat((lookup as DocumentationLookup.Found).content.bytes.toString(Charsets.UTF_8))
			.isEqualTo("Hello Kotlin!")
	}

	@Test
	fun `a template pulls in another one by name, so pages can share partials`() {
		val database =
			templatedDatabase(
				"page.peb" to """Hello {{ who }}! {% include "nav.peb" %}""",
				"nav.peb" to "[nav]",
			)
		every { SQLiteDatabase.openDatabase(any(), isNull(), any()) } returns database

		val lookup = source().lookup("k/html/basic-syntax.html")

		assertThat((lookup as DocumentationLookup.Found).content.bytes.toString(Charsets.UTF_8))
			.isEqualTo("Hello Kotlin! [nav]")
	}

	@Test
	fun `a template inherits a layout, filling in its blocks`() {
		val database =
			templatedDatabase(
				"page.peb" to """{% extends "layout.pebble" %}{% block body %}Hello {{ who }}!{% endblock %}""",
				"layout.pebble" to "<main>{% block body %}{% endblock %}</main>",
			)
		every { SQLiteDatabase.openDatabase(any(), isNull(), any()) } returns database

		val lookup = source().lookup("k/html/basic-syntax.html")

		assertThat((lookup as DocumentationLookup.Found).content.bytes.toString(Charsets.UTF_8))
			.isEqualTo("<main>Hello Kotlin!</main>")
	}

	// The ADFA-5405 regression: with Pebble's StringLoader the reference resolved to itself, so the
	// page rendered the literal text "nav.peb" and nothing said the partial was missing.
	@Test
	fun `a reference to a template that is not in the database fails the lookup`() {
		val database = templatedDatabase("page.peb" to """Hello! {% include "nav.peb" %}""")
		every { SQLiteDatabase.openDatabase(any(), isNull(), any()) } returns database

		assertThat(source().lookup("k/html/basic-syntax.html")).isInstanceOf(DocumentationLookup.Failed::class.java)
	}

	@Test
	fun `a well-known template renders by name, with no Content row of its own`() {
		val database = templatedDatabase("bookshelf" to "Books for {{ who }}")
		every { SQLiteDatabase.openDatabase(any(), isNull(), any()) } returns database

		val rendered =
			source().renderNamedTemplate("bookshelf", "/bookshelf") { """{"who": "Kotlin"}""".toByteArray() }

		assertThat(rendered.toString(Charsets.UTF_8)).isEqualTo("Books for Kotlin")
	}

	@Test
	fun `a template context that carries nothing is rejected rather than rendered`() {
		val database = templatedDatabase("bookshelf" to "Books for {{ who }}")
		every { SQLiteDatabase.openDatabase(any(), isNull(), any()) } returns database
		val source = source()

		assertThrows(IllegalStateException::class.java) {
			source.renderNamedTemplate("bookshelf", "/bookshelf") { "null".toByteArray() }
		}
	}

	// ADFA-5405 made cycles reachable: before it, a reference resolved to itself and never recursed.
	// Pebble has no cycle detection and resolves one by recursing until the stack ends, and a
	// StackOverflowError is an Error -- it passes through every catch on the serving path, so the
	// client would get a closed socket with no status line.
	@Test
	fun `a reference cycle between templates fails the lookup instead of unwinding the stack`() {
		val database =
			templatedDatabase(
				"page.peb" to """{% include "other.peb" %}""",
				"other.peb" to """{% include "page.peb" %}""",
			)
		every { SQLiteDatabase.openDatabase(any(), isNull(), any()) } returns database

		val lookup = source().lookup("k/html/basic-syntax.html")

		assertThat(lookup).isInstanceOf(DocumentationLookup.Failed::class.java)
		assertThat((lookup as DocumentationLookup.Failed).cause).hasMessageThat().contains("reference cycle")
	}

	// PebbleException formats its message as "<text> (<file>:<line>)" and the loader throws with both
	// null, so passing message straight to a response body appends "(?:?)".
	@Test
	fun `a failed render reports the engine's text without its placeholder padding`() {
		val database = templatedDatabase("page.peb" to """Hello! {% include "nav.peb" %}""")
		every { SQLiteDatabase.openDatabase(any(), isNull(), any()) } returns database

		val lookup = source().lookup("k/html/basic-syntax.html") as DocumentationLookup.Failed

		assertThat(lookup.cause).hasMessageThat().isEqualTo("Template 'nav.peb' not found in the database")
	}

	// The other half of the padding fix: getPebbleMessage() drops the "(<file>:<line>)" suffix, which
	// is what a parse error uses to say which template and line to go and fix. Only the loader's own
	// throws, which carry neither, should lose it.
	@Test
	fun `a broken template keeps the file and line the engine reports`() {
		val database = templatedDatabase("page.peb" to "Hello\n{% if %}")
		every { SQLiteDatabase.openDatabase(any(), isNull(), any()) } returns database

		val lookup = source().lookup("k/html/basic-syntax.html") as DocumentationLookup.Failed

		assertThat(lookup.cause).hasMessageThat().contains("page.peb")
		assertThat(lookup.cause).hasMessageThat().contains("2")
	}

	@Test
	fun `a template is compiled once and reused for the next page that needs it`() {
		val database = templatedDatabase("page.peb" to "Hello {{ who }}!")
		every { SQLiteDatabase.openDatabase(any(), isNull(), any()) } returns database

		val source = source()
		repeat(3) { source.lookup("k/html/basic-syntax.html") }

		verify(exactly = 1) { database.rawQuery(match { it.contains("WHERE id = ?") }, arrayOf("7")) }
		verify(exactly = 1) { database.rawQuery(match { it.contains("WHERE name = ?") }, arrayOf("page.peb")) }
	}

	@Test
	fun `a missing template row is reported as a failed lookup, not a crash`() {
		val database =
			mockk<SQLiteDatabase>(relaxed = true) {
				every { rawQuery(match { it.contains("FROM   Content") }, any()) } returns
					contentCursor(bytes = "{}".toByteArray(), templateId = 7)
				every { rawQuery(match { it.contains("WHERE id = ?") }, arrayOf("7")) } returns missingRowCursor()
			}
		every { SQLiteDatabase.openDatabase(any(), isNull(), any()) } returns database

		assertThat(source().lookup("k/html/basic-syntax.html")).isInstanceOf(DocumentationLookup.Failed::class.java)
	}

	// ADFA-6618: the pipeline drops Help+Manual's jstopics copies, and the page's JSON stands in.
	@Test
	fun `a missing jstopics file is rendered from its page's JSON with the jstopics template`() {
		every { SQLiteDatabase.openDatabase(any(), isNull(), any()) } returns jsTopicDatabase()

		val lookup = jsTopic("i/jstopics/debug-enable.js") as DocumentationLookup.Found

		assertThat(lookup.content.bytes.toString(Charsets.UTF_8)).isEqualTo("""hmLoadTopic({hmTitle:"Enable the debugger"});""")
		assertThat(lookup.content.mimeType).isEqualTo("text/javascript")
	}

	@Test
	fun `a stored jstopics row is served as it is, with no rendering`() {
		every { SQLiteDatabase.openDatabase(any(), isNull(), any()) } returns
			jsTopicDatabase(storedCopies = mapOf("i/jstopics/debug-enable.js" to "stored"))

		val lookup = jsTopic("i/jstopics/debug-enable.js") as DocumentationLookup.Found

		assertThat(lookup.content.bytes.toString(Charsets.UTF_8)).isEqualTo("stored")
	}

	// The raw form misses and its page would render, but the decoded form has a stored row: it wins.
	@Test
	fun `a stored jstopics row under the decoded path wins over rendering the raw one`() {
		every { SQLiteDatabase.openDatabase(any(), isNull(), any()) } returns
			jsTopicDatabase(page = "i/a%20b.html", storedCopies = mapOf("i/jstopics/a b.js" to "stored"))

		val lookup = jsTopic("i/jstopics/a%20b.js") as DocumentationLookup.Found

		assertThat(lookup.content.bytes.toString(Charsets.UTF_8)).isEqualTo("stored")
	}

	@Test
	fun `a jstopics file is rendered from a page found only under the decoded path`() {
		every { SQLiteDatabase.openDatabase(any(), isNull(), any()) } returns jsTopicDatabase(page = "i/a b.html")

		val result = source().lookupRequestPath("i/jstopics/a%20b.js")

		assertThat(result.queriedPath).isEqualTo("i/jstopics/a b.js")
		assertThat((result.lookup as DocumentationLookup.Found).content.bytes.toString(Charsets.UTF_8))
			.isEqualTo("""hmLoadTopic({hmTitle:"Enable the debugger"});""")
	}

	@Test
	fun `a jstopics miss stays not found when the database has no jstopics template`() {
		every { SQLiteDatabase.openDatabase(any(), isNull(), any()) } returns jsTopicDatabase(jsTopicTemplate = null)

		assertThat(jsTopic("i/jstopics/debug-enable.js")).isEqualTo(DocumentationLookup.NotFound)
	}

	@Test
	fun `a jstopics miss stays not found when its page is plain HTML`() {
		every { SQLiteDatabase.openDatabase(any(), isNull(), any()) } returns jsTopicDatabase(pageTemplateId = 0)

		assertThat(jsTopic("i/jstopics/debug-enable.js")).isEqualTo(DocumentationLookup.NotFound)
	}

	@Test
	fun `a jstopics miss stays not found when its page uses another template`() {
		every { SQLiteDatabase.openDatabase(any(), isNull(), any()) } returns jsTopicDatabase(pageTemplate = "page.peb")

		assertThat(jsTopic("i/jstopics/debug-enable.js")).isEqualTo(DocumentationLookup.NotFound)
	}

	@Test
	fun `a jstopics miss stays not found when it has no page`() {
		every { SQLiteDatabase.openDatabase(any(), isNull(), any()) } returns jsTopicDatabase()

		assertThat(jsTopic("i/jstopics/no-such-topic.js")).isEqualTo(DocumentationLookup.NotFound)
	}

	// The path is the page's: WebServer quotes it in its "Corrupt database" answer, so a bug report
	// carries a query that returns the duplicated rows, not the jstopics path, which has none.
	@Test
	fun `a duplicated page row is reported as ambiguous, under the page's path`() {
		every { SQLiteDatabase.openDatabase(any(), isNull(), any()) } returns jsTopicDatabase(pageRows = 2)

		val result = source().lookupRequestPath("i/jstopics/debug-enable.js")

		assertThat(result.lookup).isEqualTo(DocumentationLookup.Ambiguous(2))
		assertThat(result.queriedPath).isEqualTo("i/debug-enable.html")
	}

	// Every shipped page is Brotli-compressed: bytes marked brotli that are not fail the decode,
	// where joining them as they are would have parsed as JSON and rendered. A successful decode
	// needs brotli4j's desktop native, which only :app's tests load: see BrotliContentSourceTest.
	@Test
	fun `a page marked brotli is decoded before it is rendered`() {
		every { SQLiteDatabase.openDatabase(any(), isNull(), any()) } returns jsTopicDatabase(pageCompression = "brotli")

		assertThat(jsTopic("i/jstopics/debug-enable.js")).isInstanceOf(DocumentationLookup.Failed::class.java)
	}

	@Test
	fun `a page split across rows is read whole before it is rendered`() {
		val json = """{"hmTitle": "Enable the debugger", "pad": "${"x".repeat(DocumentationContentSource.CONTENT_CHUNK_SIZE)}"}"""
		val bytes = json.toByteArray()
		val first = bytes.copyOfRange(0, DocumentationContentSource.CONTENT_CHUNK_SIZE)
		val rest = bytes.copyOfRange(DocumentationContentSource.CONTENT_CHUNK_SIZE, bytes.size)
		val database = jsTopicDatabase(pageBytes = first)
		every { database.rawQuery(match { it.startsWith("SELECT content FROM Content") }, arrayOf("i/debug-enable.html-1")) } returns
			mockk(relaxed = true) {
				every { moveToFirst() } returns true
				every { getBlob(0) } returns rest
			}
		every { SQLiteDatabase.openDatabase(any(), isNull(), any()) } returns database

		val lookup = jsTopic("i/jstopics/debug-enable.js") as DocumentationLookup.Found

		assertThat(lookup.content.bytes.toString(Charsets.UTF_8)).isEqualTo("""hmLoadTopic({hmTitle:"Enable the debugger"});""")
	}

	@Test
	fun `a miss outside jstopics is not looked up as a page`() {
		val database = jsTopicDatabase()
		every { SQLiteDatabase.openDatabase(any(), isNull(), any()) } returns database

		assertThat(jsTopic("i/js/debug-enable.js")).isEqualTo(DocumentationLookup.NotFound)
		assertThat(jsTopic("i/jstopics/sub/debug-enable.js")).isEqualTo(DocumentationLookup.NotFound)
		verify(exactly = 0) { database.rawQuery(match { it.contains("FROM   Content") }, match { it[0].endsWith(".html") }) }
	}

	/** The lookup both transports make for a request path. */
	private fun jsTopic(path: String) = source().lookupRequestPath(path).lookup

	/**
	 * A database whose [page] row is templated with [pageTemplateId], named [pageTemplate], and
	 * holds a topic's JSON; [storedCopies] are stored jstopics rows by path; [jsTopicTemplate] is
	 * the `helpmanual-jstopic.peb` body, if the database has one. Every other Content path misses.
	 */
	private fun jsTopicDatabase(
		page: String = "i/debug-enable.html",
		pageTemplateId: Int = 5,
		pageTemplate: String = "helpmanual-topic.peb",
		pageRows: Int = 1,
		pageCompression: String = "none",
		pageBytes: ByteArray = """{"hmTitle": "Enable the debugger"}""".toByteArray(),
		jsTopicTemplate: String? = """hmLoadTopic({hmTitle:"{{ hmTitle }}"});""",
		storedCopies: Map<String, String> = emptyMap(),
	): SQLiteDatabase =
		mockk<SQLiteDatabase>(relaxed = true) {
			every { rawQuery(match { it.contains("FROM   Content") }, any()) } answers { missingRowCursor() }
			every { rawQuery(match { it.contains("FROM   Content") }, arrayOf(page)) } answers {
				contentCursor(bytes = pageBytes, compression = pageCompression, templateId = pageTemplateId, rowCount = pageRows)
			}
			for ((path, body) in storedCopies) {
				every { rawQuery(match { it.contains("FROM   Content") }, arrayOf(path)) } answers {
					contentCursor(bytes = body.toByteArray(), mimeType = "text/javascript")
				}
			}
			every { rawQuery(match { it.contains("WHERE id = ?") }, arrayOf(pageTemplateId.toString())) } answers {
				mockk(relaxed = true) {
					every { count } returns 1
					every { moveToFirst() } returns true
					every { getString(0) } returns pageTemplate
				}
			}
			// One cursor answers both the loader's existence count and its read of the body.
			every { rawQuery(match { it.contains("WHERE name = ?") }, arrayOf("helpmanual-jstopic.peb")) } answers {
				if (jsTopicTemplate == null) {
					missingRowCursor()
				} else {
					mockk(relaxed = true) {
						every { count } returns 1
						every { moveToFirst() } returns true
						every { getInt(0) } returns 1
						every { getBlob(0) } returns jsTopicTemplate.toByteArray()
					}
				}
			}
		}

	@Test
	fun `a newer debug database is swapped in, and the generation says so`() {
		val installed = database(contentCursor(bytes = "installed".toByteArray()))
		val debug = database(contentCursor(bytes = "debug".toByteArray()))
		every { SQLiteDatabase.openDatabase(installedFile.absolutePath, isNull(), any()) } returns installed
		every { SQLiteDatabase.openDatabase(debugFile.absolutePath, isNull(), any()) } returns debug

		// Zero interval: the rate limit on the stat is not what this test is about.
		val source = source(debugCheckIntervalMs = 0)

		assertThat((source.lookup("p") as DocumentationLookup.Found).content.bytes.toString(Charsets.UTF_8))
			.isEqualTo("installed")
		val generationBefore = source.generation

		debugFile.writeText("newer")
		debugFile.setLastModified(installedFile.lastModified() + 60_000)

		assertThat((source.lookup("p") as DocumentationLookup.Found).content.bytes.toString(Charsets.UTF_8))
			.isEqualTo("debug")
		assertThat(source.generation).isGreaterThan(generationBefore)
		verify { installed.close() }
	}

	@Test
	fun `a debug-database swap drops the compiled templates, so pages render from the new database`() {
		val installed = templatedDatabase("page.peb" to "Hello {{ who }}!")
		val debug = templatedDatabase("page.peb" to "Goodbye {{ who }}!")
		every { SQLiteDatabase.openDatabase(installedFile.absolutePath, isNull(), any()) } returns installed
		every { SQLiteDatabase.openDatabase(debugFile.absolutePath, isNull(), any()) } returns debug

		val source = source(debugCheckIntervalMs = 0)

		// Compiles and caches the installed database's template.
		assertThat((source.lookup("p") as DocumentationLookup.Found).content.bytes.toString(Charsets.UTF_8))
			.isEqualTo("Hello Kotlin!")

		debugFile.writeText("newer")
		debugFile.setLastModified(installedFile.lastModified() + 60_000)

		// Same template id in the new database: only a cleared cache recompiles it from there.
		assertThat((source.lookup("p") as DocumentationLookup.Found).content.bytes.toString(Charsets.UTF_8))
			.isEqualTo("Goodbye Kotlin!")
	}

	// ADFA-3305: from documentation version 3 every template is Brotli-compressed; below it they are
	// plain UTF-8, and still read that way, since an app update never replaces the installed file.
	// The decode here reverses the bytes, so a test can tell whether a template went through it.
	@Test
	fun `a database below version 3 has its templates read as plain text, without the decode`() {
		val database = templatedDatabase("page.peb" to "Hello {{ who }}!", declaredMajorVersion = 2)
		every { SQLiteDatabase.openDatabase(any(), isNull(), any()) } returns database

		val lookup = source(decode = reversing).lookup("k/html/basic-syntax.html")

		assertThat((lookup as DocumentationLookup.Found).content.bytes.toString(Charsets.UTF_8)).isEqualTo("Hello Kotlin!")
	}

	@Test
	fun `a database declaring no version is read the same way`() {
		val database = templatedDatabase("page.peb" to "Hello {{ who }}!", declaredMajorVersion = null)
		every { SQLiteDatabase.openDatabase(any(), isNull(), any()) } returns database

		val lookup = source(decode = reversing).lookup("k/html/basic-syntax.html")

		assertThat((lookup as DocumentationLookup.Found).content.bytes.toString(Charsets.UTF_8)).isEqualTo("Hello Kotlin!")
	}

	@Test
	fun `a version 3 database has its templates decoded`() {
		val database = templatedDatabase("page.peb" to "Hello {{ who }}!".reversed())
		every { SQLiteDatabase.openDatabase(any(), isNull(), any()) } returns database

		val lookup = source(decode = reversing).lookup("k/html/basic-syntax.html")

		assertThat((lookup as DocumentationLookup.Found).content.bytes.toString(Charsets.UTF_8)).isEqualTo("Hello Kotlin!")
	}

	// Most real templates hold non-ASCII text, U+00A0 above all. Compared as the page's bytes, so a
	// template read in any charset but UTF-8, plain or decoded, fails here.
	@Test
	fun `a version 2 template's non-ASCII text renders as UTF-8`() {
		val database = templatedDatabase("page.peb" to "$NON_ASCII {{ who }}", declaredMajorVersion = 2)
		every { SQLiteDatabase.openDatabase(any(), isNull(), any()) } returns database

		val lookup = source(decode = reversing).lookup("k/html/basic-syntax.html")

		assertThat((lookup as DocumentationLookup.Found).content.bytes)
			.isEqualTo("$NON_ASCII Kotlin".toByteArray(Charsets.UTF_8))
	}

	@Test
	fun `a version 3 template's non-ASCII text renders as UTF-8`() {
		val database = templatedDatabase("page.peb" to "$NON_ASCII {{ who }}".reversed())
		every { SQLiteDatabase.openDatabase(any(), isNull(), any()) } returns database

		val lookup = source(decode = reversingChars).lookup("k/html/basic-syntax.html")

		assertThat((lookup as DocumentationLookup.Found).content.bytes)
			.isEqualTo("$NON_ASCII Kotlin".toByteArray(Charsets.UTF_8))
	}

	// Never rendered as whatever the bytes happen to be: the page fails, naming the template.
	@Test
	fun `a version 3 template that does not decode fails its page, naming the template`() {
		val database = templatedDatabase("page.peb" to "Hello {{ who }}!")
		every { SQLiteDatabase.openDatabase(any(), isNull(), any()) } returns database

		val lookup = source(decode = { _, _ -> throw IOException("Brotli decoder failed") }).lookup("p")

		assertThat(lookup).isInstanceOf(DocumentationLookup.Failed::class.java)
		assertThat((lookup as DocumentationLookup.Failed).cause).isInstanceOf(TemplateRenderException::class.java)
		assertThat(lookup.cause).hasMessageThat().isEqualTo("Cannot decode template 'page.peb'")
	}

	// The version is read before the engine runs. Read inside the loader instead, a database error
	// would arrive as a template that does not decode -- the wrong diagnosis, and a template failure,
	// whose message both transports send.
	@Test
	fun `a failure to read the version is reported as itself, not as a template that does not decode`() {
		val database = templatedDatabase("page.peb" to "Hello {{ who }}!")
		every { database.rawQuery(match { it.contains("FROM   DocumentationDatabaseVersion") }, any()) } throws
			SQLiteException("disk I/O error (code 10)")
		every { SQLiteDatabase.openDatabase(any(), isNull(), any()) } returns database

		val lookup = source(decode = reversing).lookup("p") as DocumentationLookup.Failed

		assertThat(lookup.cause).isInstanceOf(SQLiteException::class.java)
	}

	@Test
	fun `a swap from a version 2 file to a version 3 file decodes the new one's templates`() {
		val installed = templatedDatabase("page.peb" to "Hello {{ who }}!", declaredMajorVersion = 2)
		val debug = templatedDatabase("page.peb" to "Goodbye {{ who }}!".reversed())
		every { SQLiteDatabase.openDatabase(installedFile.absolutePath, isNull(), any()) } returns installed
		every { SQLiteDatabase.openDatabase(debugFile.absolutePath, isNull(), any()) } returns debug
		val source = source(debugCheckIntervalMs = 0, decode = reversing)

		assertThat((source.lookup("p") as DocumentationLookup.Found).content.bytes.toString(Charsets.UTF_8))
			.isEqualTo("Hello Kotlin!")

		debugFile.writeText("newer")
		debugFile.setLastModified(installedFile.lastModified() + 60_000)

		assertThat((source.lookup("p") as DocumentationLookup.Found).content.bytes.toString(Charsets.UTF_8))
			.isEqualTo("Goodbye Kotlin!")
	}

	@Test
	fun `a swap from a version 3 file to a version 2 file reads the new one's templates as plain text`() {
		val installed = templatedDatabase("page.peb" to "Hello {{ who }}!".reversed())
		val debug = templatedDatabase("page.peb" to "Goodbye {{ who }}!", declaredMajorVersion = 2)
		every { SQLiteDatabase.openDatabase(installedFile.absolutePath, isNull(), any()) } returns installed
		every { SQLiteDatabase.openDatabase(debugFile.absolutePath, isNull(), any()) } returns debug
		val source = source(debugCheckIntervalMs = 0, decode = reversing)

		assertThat((source.lookup("p") as DocumentationLookup.Found).content.bytes.toString(Charsets.UTF_8))
			.isEqualTo("Hello Kotlin!")

		debugFile.writeText("newer")
		debugFile.setLastModified(installedFile.lastModified() + 60_000)

		assertThat((source.lookup("p") as DocumentationLookup.Found).content.bytes.toString(Charsets.UTF_8))
			.isEqualTo("Goodbye Kotlin!")
	}

	/**
	 * A database whose single Content row is templated with id 7, and whose `Templates` rows are
	 * [templates] as name-to-body pairs. Template id 7 is the first pair; the rest are reachable
	 * only by name, which is how one template references another. It declares
	 * [declaredMajorVersion], by default 3, the first whose templates this build reads.
	 */
	private fun templatedDatabase(
		vararg templates: Pair<String, String>,
		declaredMajorVersion: Int? = 3,
	): SQLiteDatabase =
		mockk<SQLiteDatabase>(relaxed = true) {
			declaresMajorVersion(declaredMajorVersion)
			every { rawQuery(match { it.contains("FROM   Content") }, any()) } returns
				contentCursor(bytes = """{"who": "Kotlin"}""".toByteArray(), templateId = 7)
			every { rawQuery(match { it.contains("WHERE id = ?") }, arrayOf("7")) } returns
				mockk(relaxed = true) {
					every { count } returns 1
					every { moveToFirst() } returns true
					every { getString(0) } returns templates.first().first
				}
			every { rawQuery(match { it.contains("WHERE name = ?") }, any()) } answers
				{
					val name = (secondArg<Array<String>>())[0]
					val body = templates.toMap()[name] ?: return@answers missingRowCursor()
					mockk(relaxed = true) {
						every { count } returns 1
						every { moveToFirst() } returns true
						every { getBlob(0) } returns body.toByteArray()
					}
				}
		}

	/** A cursor over no rows, for a `Templates` name or id that the database does not have. */
	private fun missingRowCursor() =
		mockk<Cursor>(relaxed = true) {
			every { count } returns 0
			every { moveToFirst() } returns false
		}

	@Test
	fun `a debug database that will not open leaves the installed one serving`() {
		val installed = database(contentCursor(bytes = "installed".toByteArray()))
		every { SQLiteDatabase.openDatabase(installedFile.absolutePath, isNull(), any()) } returns installed
		every { SQLiteDatabase.openDatabase(debugFile.absolutePath, isNull(), any()) } throws IllegalStateException("corrupt")

		val source = source(debugCheckIntervalMs = 0)
		source.lookup("p")
		val generationBefore = source.generation

		debugFile.writeText("corrupt")
		debugFile.setLastModified(installedFile.lastModified() + 60_000)

		assertThat((source.lookup("p") as DocumentationLookup.Found).content.bytes.toString(Charsets.UTF_8))
			.isEqualTo("installed")
		assertThat(source.generation).isEqualTo(generationBefore)
		verify(exactly = 0) { installed.close() }
	}

	@Test
	fun `a failed debug swap is not retried until the file changes again`() {
		val installed = database(contentCursor())
		every { SQLiteDatabase.openDatabase(installedFile.absolutePath, isNull(), any()) } returns installed
		every { SQLiteDatabase.openDatabase(debugFile.absolutePath, isNull(), any()) } throws IllegalStateException("corrupt")

		val source = source(debugCheckIntervalMs = 0)
		debugFile.writeText("corrupt")
		debugFile.setLastModified(installedFile.lastModified() + 60_000)

		repeat(3) { source.lookup("p") }

		verify(exactly = 1) { SQLiteDatabase.openDatabase(debugFile.absolutePath, isNull(), any()) }
	}

	// The installers rewrite Environment.DOC_DB in place, so a handle cached for the process's life
	// has to notice the file changing underneath it and reopen (ADFA-5176 review).
	@Test
	fun `an installed database rewritten in place is reopened, and the generation says so`() {
		val before = database(contentCursor(bytes = "before".toByteArray()))
		val after = database(contentCursor(bytes = "after".toByteArray()))
		every { SQLiteDatabase.openDatabase(installedFile.absolutePath, isNull(), any()) } returnsMany listOf(before, after)

		val source = source(debugCheckIntervalMs = 0)

		assertThat((source.lookup("p") as DocumentationLookup.Found).content.bytes.toString(Charsets.UTF_8))
			.isEqualTo("before")
		val generationBefore = source.generation

		installedFile.writeText("rewritten")
		installedFile.setLastModified(installedFile.lastModified() + 60_000)

		assertThat((source.lookup("p") as DocumentationLookup.Found).content.bytes.toString(Charsets.UTF_8))
			.isEqualTo("after")
		assertThat(source.generation).isGreaterThan(generationBefore)
		verify { before.close() }
	}

	@Test
	fun `a failed installed-database reopen is not retried until the file changes again`() {
		val before = database(contentCursor(bytes = "before".toByteArray()))
		every { SQLiteDatabase.openDatabase(installedFile.absolutePath, isNull(), any()) } returns before

		val source = source(debugCheckIntervalMs = 0)
		source.lookup("p")

		// The rewrite is detected but the reopen fails, as it would mid-install.
		every { SQLiteDatabase.openDatabase(installedFile.absolutePath, isNull(), any()) } throws IllegalStateException("mid-install")
		installedFile.writeText("rewritten")
		installedFile.setLastModified(installedFile.lastModified() + 60_000)

		repeat(3) { source.lookup("p") }

		// One initial open plus exactly one failed reopen attempt; the old handle keeps serving.
		verify(exactly = 2) { SQLiteDatabase.openDatabase(installedFile.absolutePath, isNull(), any()) }
		assertThat((source.lookup("p") as DocumentationLookup.Found).content.bytes.toString(Charsets.UTF_8))
			.isEqualTo("before")
	}

	@Test
	fun `withDatabase runs against the open database`() {
		val database = database(contentCursor())
		every { SQLiteDatabase.openDatabase(any(), isNull(), any()) } returns database

		val seen = source().withDatabase { it }

		assertThat(seen).isSameInstanceAs(database)
	}

	@Test
	fun `close closes the handle, and closing twice is harmless`() {
		val database = database(contentCursor())
		every { SQLiteDatabase.openDatabase(any(), isNull(), any()) } returns database

		val source = source()
		source.lookup("p")
		source.close()
		source.close()

		verify(exactly = 1) { database.close() }
	}

	// close() is terminal: a straggler call afterwards must not reopen a handle that nothing will
	// ever close again.
	@Test
	fun `a lookup after close reports not found instead of reopening`() {
		val database = database(contentCursor())
		every { SQLiteDatabase.openDatabase(any(), isNull(), any()) } returns database

		val source = source()
		source.lookup("p")
		source.close()

		assertThat(source.lookup("p")).isEqualTo(DocumentationLookup.NotFound)
		verify(exactly = 1) { SQLiteDatabase.openDatabase(any(), isNull(), any()) }
	}

	@Test
	fun `withDatabase after close throws instead of reopening`() {
		val database = database(contentCursor())
		every { SQLiteDatabase.openDatabase(any(), isNull(), any()) } returns database

		val source = source()
		source.lookup("p")
		source.close()

		assertThrows(IllegalStateException::class.java) { source.withDatabase { } }
		verify(exactly = 1) { SQLiteDatabase.openDatabase(any(), isNull(), any()) }
	}

	// refreshDatabase() used to fall through to the swap check after close(), and a newer debug
	// database then reached switchToDatabase(), which reopened a handle nothing would ever close.
	@Test
	fun `refreshDatabase after close leaves a newer debug database alone instead of reopening`() {
		val database = database(contentCursor())
		every { SQLiteDatabase.openDatabase(any(), isNull(), any()) } returns database

		val source = source(debugCheckIntervalMs = 0)
		source.lookup("p")
		source.close()

		debugFile.writeText("newer")
		debugFile.setLastModified(installedFile.lastModified() + 60_000)

		source.refreshDatabase()

		assertThat(source.lookup("p")).isEqualTo(DocumentationLookup.NotFound)
		verify(exactly = 1) { SQLiteDatabase.openDatabase(any(), isNull(), any()) }
	}

	// Same hole, other branch: the installed file rewritten after close() must not be reopened.
	@Test
	fun `refreshDatabase after close leaves a rewritten installed database alone instead of reopening`() {
		val database = database(contentCursor())
		every { SQLiteDatabase.openDatabase(any(), isNull(), any()) } returns database

		val source = source(debugCheckIntervalMs = 0)
		source.lookup("p")
		source.close()

		installedFile.writeText("rewritten")
		installedFile.setLastModified(installedFile.lastModified() + 60_000)

		source.refreshDatabase()

		assertThat(source.lookup("p")).isEqualTo(DocumentationLookup.NotFound)
		verify(exactly = 1) { SQLiteDatabase.openDatabase(any(), isNull(), any()) }
	}

	private companion object {
		// A no-break space (in Latin-1) and an em dash and an ellipsis (outside it), as real templates hold.
		const val NON_ASCII = "a b — c…"
	}
}
