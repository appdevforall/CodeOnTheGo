package com.itsaky.androidide.localWebServer

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.itsaky.androidide.documentation.DocumentationContentSource
import com.itsaky.androidide.documentation.DocumentationLookup
import com.itsaky.androidide.logging.provider.IdeLogRouter
import com.itsaky.androidide.utils.BrotliDictionaryCodec
import com.itsaky.androidide.utils.toDirectByteBuffer
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertArrayEquals
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
 * Templates read through [DocumentationContentSource]'s own decode (ADFA-3305): from documentation
 * version 3 every `Templates` row is Brotli-compressed against the database's dictionary, and below
 * it they are plain UTF-8. `:common`'s tests cannot run that decode, since they lack brotli4j's
 * desktop native (see common/build.gradle.kts), so they render through a stand-in decode; these
 * cover the real one.
 */
class CompressedTemplateTest {
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
	fun `a template compressed against the database's dictionary renders`() {
		val dictionary = dictionary("kotlin")
		every { SQLiteDatabase.openDatabase(any(), isNull(), any()) } returns
			database(dictionary, "page.peb" to stored(page("kotlin"), dictionary))

		assertEquals(page("kotlin").replace("{{ who }}", "Kotlin"), render(source()))
	}

	// 640 of the 3.0.0 build's 1,126 templates hold non-ASCII text, most of them U+00A0. Compared as
	// the page's bytes, so a template decoded to text in any charset but UTF-8 fails here.
	@Test
	fun `a compressed template's non-ASCII text renders as UTF-8`() {
		val dictionary = dictionary("kotlin")
		val template = "$NON_ASCII ${page("kotlin")}"
		every { SQLiteDatabase.openDatabase(any(), isNull(), any()) } returns
			database(dictionary, "page.peb" to stored(template, dictionary))

		assertArrayEquals(template.replace("{{ who }}", "Kotlin").toByteArray(Charsets.UTF_8), renderedBytes(source()))
	}

	@Test
	fun `a version 2 database's plain non-ASCII template renders as UTF-8`() {
		val dictionary = dictionary("kotlin")
		val template = "$NON_ASCII ${page("kotlin")}"
		every { SQLiteDatabase.openDatabase(any(), isNull(), any()) } returns
			database(dictionary, "page.peb" to template.toByteArray(Charsets.UTF_8), declaredMajorVersion = 2)

		assertArrayEquals(template.replace("{{ who }}", "Kotlin").toByteArray(Charsets.UTF_8), renderedBytes(source()))
	}

	@Test
	fun `an included template is decoded too`() {
		val dictionary = dictionary("kotlin")
		every { SQLiteDatabase.openDatabase(any(), isNull(), any()) } returns
			database(
				dictionary,
				"page.peb" to stored("""{% include "nav.peb" %}|${page("kotlin")}""", dictionary),
				"nav.peb" to stored(nav("kotlin"), dictionary),
			)

		assertEquals(nav("kotlin") + "|" + page("kotlin").replace("{{ who }}", "Kotlin"), render(source()))
	}

	// An app update never replaces the installed database, so a 2.x file -- plain templates, though it
	// carries a dictionary for its rows -- has to keep rendering its templated pages.
	@Test
	fun `a version 2 database's plain template renders`() {
		val dictionary = dictionary("kotlin")
		every { SQLiteDatabase.openDatabase(any(), isNull(), any()) } returns
			database(dictionary, "page.peb" to page("kotlin").toByteArray(), declaredMajorVersion = 2)

		assertEquals(page("kotlin").replace("{{ who }}", "Kotlin"), render(source()))
	}

	// A version 3 file whose template was left plain, as a release step that skipped compression
	// would leave it. The page fails, naming the template, and the log names the database too --
	// never a page rendered from whatever the Brotli decoder made of plain text.
	@Test
	fun `a version 3 template that does not decode fails its page and is logged`() {
		val dictionary = dictionary("kotlin")
		every { SQLiteDatabase.openDatabase(any(), isNull(), any()) } returns
			database(dictionary, "page.peb" to page("kotlin").toByteArray())
		val errors = mutableListOf<String>()
		val sink = IdeLogRouter.ExternalSink { level, _, message, _ -> if (level == Level.ERROR) errors += message }
		IdeLogRouter.addSink(sink)
		val lookup =
			try {
				source().lookup("k/html/basic-syntax.html")
			} finally {
				IdeLogRouter.removeSink(sink)
			}

		val failed = lookup as DocumentationLookup.Failed
		assertEquals("Cannot decode template 'page.peb'", failed.cause.message)
		assertTrue(errors.toString(), errors.any { it.contains("'page.peb'") && it.contains(DATABASE_PATH) })
	}

	// The two dictionaries are the same length and differ only in bytes the templates reference, so
	// decoding the new database's template with the old database's dictionary does not fail: it
	// returns different text (see BrotliDictionaryDecodeTest). Only a codec rebuilt for the new
	// database, and a compiled-template cache dropped with the old one, renders the new page.
	@Test
	fun `after a swap, templates are decoded with the new database's dictionary`() {
		val installedDictionary = dictionary("kotlin")
		val debugDictionary = dictionary("scalax")
		every { SQLiteDatabase.openDatabase(installedFile.absolutePath, isNull(), any()) } returns
			database(installedDictionary, "page.peb" to stored(page("kotlin"), installedDictionary))
		every { SQLiteDatabase.openDatabase(debugFile.absolutePath, isNull(), any()) } returns
			database(debugDictionary, "page.peb" to stored(page("scalax"), debugDictionary))
		val source = source()

		assertEquals(page("kotlin").replace("{{ who }}", "Kotlin"), render(source))

		swapToDebugDatabase()

		assertEquals(page("scalax").replace("{{ who }}", "Kotlin"), render(source))
	}

	@Test
	fun `a swap from a version 2 file to a version 3 file renders both`() {
		val dictionary = dictionary("kotlin")
		every { SQLiteDatabase.openDatabase(installedFile.absolutePath, isNull(), any()) } returns
			database(dictionary, "page.peb" to page("kotlin").toByteArray(), declaredMajorVersion = 2)
		every { SQLiteDatabase.openDatabase(debugFile.absolutePath, isNull(), any()) } returns
			database(dictionary, "page.peb" to stored(page("scalax"), dictionary))
		val source = source()

		assertEquals(page("kotlin").replace("{{ who }}", "Kotlin"), render(source))

		swapToDebugDatabase()

		assertEquals(page("scalax").replace("{{ who }}", "Kotlin"), render(source))
	}

	@Test
	fun `a swap from a version 3 file to a version 2 file renders both`() {
		val dictionary = dictionary("kotlin")
		every { SQLiteDatabase.openDatabase(installedFile.absolutePath, isNull(), any()) } returns
			database(dictionary, "page.peb" to stored(page("kotlin"), dictionary))
		every { SQLiteDatabase.openDatabase(debugFile.absolutePath, isNull(), any()) } returns
			database(dictionary, "page.peb" to page("scalax").toByteArray(), declaredMajorVersion = 2)
		val source = source()

		assertEquals(page("kotlin").replace("{{ who }}", "Kotlin"), render(source))

		swapToDebugDatabase()

		assertEquals(page("scalax").replace("{{ who }}", "Kotlin"), render(source))
	}

	private fun source() = DocumentationContentSource(installedFile, debugFile, 0)

	private fun swapToDebugDatabase() {
		debugFile.writeText("newer")
		debugFile.setLastModified(installedFile.lastModified() + 60_000)
	}

	private fun render(source: DocumentationContentSource): String = renderedBytes(source).toString(Charsets.UTF_8)

	private fun renderedBytes(source: DocumentationContentSource): ByteArray =
		(source.lookup("k/html/basic-syntax.html") as DocumentationLookup.Found).content.bytes

	private fun dictionary(word: String) = "$SHARED $word interface companion object ".repeat(400)

	private fun page(word: String) = "$SHARED $word {{ who }} interface companion object ".repeat(6)

	private fun nav(word: String) = "<nav>$SHARED $word</nav>".repeat(4)

	/**
	 * [template] as the pipeline stores it: Brotli against [dictionary]. Checked to reference the
	 * dictionary, failing without it, since a stream that never matched into the dictionary decodes
	 * the same either way and would not show that the dictionary is attached.
	 */
	private fun stored(
		template: String,
		dictionary: String,
	): ByteArray {
		val bytes = BrotliDictionaryCodec(toDirectByteBuffer(dictionary.toByteArray())).compress(template.toByteArray())
		assertThrows(IOException::class.java) { BrotliDictionaryCodec(null).decompress(ByteArrayInputStream(bytes)) }
		return bytes
	}

	/**
	 * A database declaring [declaredMajorVersion] with [dictionary] as its CompressionDictionary, one
	 * Content row -- k/html/basic-syntax.html, templated with id 7, the first of [templates] -- and
	 * [templates] as its `Templates` rows, by name.
	 */
	private fun database(
		dictionary: String,
		vararg templates: Pair<String, ByteArray>,
		declaredMajorVersion: Int = 3,
	): SQLiteDatabase =
		mockk(relaxed = true) {
			every { path } returns DATABASE_PATH
			every { rawQuery(match { it.contains("FROM   Content") }, any()) } returns
				cursor(rows = 1) {
					every { getBlob(0) } returns """{"who": "Kotlin"}""".toByteArray()
					every { getString(1) } returns "text/html"
					every { getString(2) } returns "none"
					every { getInt(3) } returns 7
				}
			every { rawQuery(match { it.contains("WHERE id = ?") }, arrayOf("7")) } returns
				cursor(rows = 1) { every { getString(0) } returns templates.first().first }
			every { rawQuery(match { it.contains("FROM Templates WHERE name = ?") }, any()) } answers {
				val body = templates.toMap()[secondArg<Array<String>>()[0]]
				cursor(rows = if (body == null) 0 else 1) { every { getBlob(0) } returns body }
			}
			every {
				rawQuery(match { it.contains("FROM   sqlite_master") && it.contains("DocumentationDatabaseVersion") }, any())
			} returns cursor(rows = 1)
			every { rawQuery(match { it.contains("FROM   DocumentationDatabaseVersion") }, any()) } returns
				cursor(rows = 1) {
					every { isNull(0) } returns false
					every { getInt(0) } returns declaredMajorVersion
					every { getInt(1) } returns 1
				}
			every { rawQuery(match { it.contains("FROM sqlite_master") && it.contains("CompressionDictionary") }, null) } returns
				cursor(rows = 1)
			every { rawQuery(match { it.contains("SELECT data FROM CompressionDictionary") }, null) } returns
				cursor(rows = 1) { every { getBlob(0) } returns dictionary.toByteArray() }
		}

	private fun cursor(
		rows: Int,
		columns: Cursor.() -> Unit = {},
	) = mockk<Cursor>(relaxed = true) {
		every { count } returns rows
		every { moveToFirst() } returns (rows > 0)
		columns()
	}

	private companion object {
		// Text the dictionaries and templates share, so a stored template matches into its dictionary.
		const val SHARED = """<div class="api-item"><h3 class="api-name">docs-sidebar toc-element"""

		const val DATABASE_PATH = "/sdcard/Download/documentation.db"

		// A no-break space (in Latin-1) and an em dash and an ellipsis (outside it), as real templates hold.
		const val NON_ASCII = "a b — c…"
	}
}
