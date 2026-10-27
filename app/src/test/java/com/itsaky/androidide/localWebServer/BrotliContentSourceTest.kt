package com.itsaky.androidide.localWebServer

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.itsaky.androidide.documentation.DocumentationContentSource
import com.itsaky.androidide.documentation.DocumentationLookup
import com.itsaky.androidide.utils.BrotliDictionaryCodec
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Brotli rows read through [DocumentationContentSource]'s own decode. `:common`'s tests can't run
 * it, since they lack brotli4j's desktop native (see common/build.gradle.kts), and every shipped
 * page is a Brotli row: a decode that always failed would pass them all.
 */
class BrotliContentSourceTest {
	@get:Rule
	val folder = TemporaryFolder()

	@Before
	fun setUp() {
		mockkStatic(SQLiteDatabase::class)
	}

	@After
	fun tearDown() {
		unmockkAll()
	}

	@Test
	fun `a stored brotli row is served decoded`() {
		every { SQLiteDatabase.openDatabase(any(), isNull(), any()) } returns database("a/page.html", brotli("<p>Hello</p>"))

		val content = found(source().lookupRequestPath("a/page.html").lookup)

		assertEquals("<p>Hello</p>", content.bytes.toString(Charsets.UTF_8))
	}

	@Test
	fun `a jstopics file is rendered from its brotli page`() {
		every { SQLiteDatabase.openDatabase(any(), isNull(), any()) } returns
			database("i/debug-enable.html", brotli("""{"hmTitle": "Enable the debugger"}"""), templateId = 5)

		val content = found(source().lookupRequestPath("i/jstopics/debug-enable.js").lookup)

		assertEquals("""hmLoadTopic({hmTitle:"Enable the debugger"});""", content.bytes.toString(Charsets.UTF_8))
		assertEquals("text/javascript", content.mimeType)
	}

	private fun source() = DocumentationContentSource(folder.newFile("documentation.db"), folder.root.resolve("debug.db"))

	private fun found(lookup: DocumentationLookup) =
		(lookup as? DocumentationLookup.Found)?.content ?: throw AssertionError("expected Found, got $lookup")

	// The database declares no dictionary, so its rows are plain Brotli.
	private fun brotli(text: String) = BrotliDictionaryCodec(null).compress(text.toByteArray())

	/**
	 * A database whose only Content row is [path], Brotli-compressed and templated with [templateId];
	 * template 5 is `helpmanual-topic.peb`, and `helpmanual-jstopic.peb` renders a topic's title.
	 */
	private fun database(
		path: String,
		bytes: ByteArray,
		templateId: Int = 0,
	): SQLiteDatabase =
		mockk(relaxed = true) {
			every { rawQuery(match { it.contains("FROM   Content") }, any()) } answers { cursor(count = 0) }
			every { rawQuery(match { it.contains("FROM   Content") }, arrayOf(path)) } answers {
				cursor {
					every { getBlob(0) } returns bytes
					every { getString(1) } returns "text/html"
					every { getString(2) } returns "brotli"
					every { getInt(3) } returns templateId
				}
			}
			every { rawQuery(match { it.contains("WHERE id = ?") }, arrayOf("5")) } answers {
				cursor { every { getString(0) } returns "helpmanual-topic.peb" }
			}
			every { rawQuery(match { it.contains("WHERE name = ?") }, arrayOf("helpmanual-jstopic.peb")) } answers {
				cursor {
					every { getInt(0) } returns 1
					every { getBlob(0) } returns """hmLoadTopic({hmTitle:"{{ hmTitle }}"});""".toByteArray()
				}
			}
		}

	private fun cursor(
		count: Int = 1,
		columns: Cursor.() -> Unit = {},
	) = mockk<Cursor>(relaxed = true) {
		every { this@mockk.count } returns count
		every { moveToFirst() } returns (count > 0)
		columns()
	}
}
