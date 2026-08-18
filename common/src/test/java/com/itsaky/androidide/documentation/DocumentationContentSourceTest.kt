package com.itsaky.androidide.documentation

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Covers the pipeline both documentation transports read through (ADFA-5176): what a lookup reports,
 * how chunked rows are reassembled, and what a debug-database swap does to the handle and to the
 * generation counter callers use to drop their per-database caches.
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

	private fun source(debugCheckIntervalMs: Long = 1_000) = DocumentationContentSource(installedFile, debugFile, debugCheckIntervalMs)

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

	@Test
	fun `lookup returns the row for a path`() {
		val database = database(contentCursor(bytes = "page".toByteArray(), mimeType = "text/html"))
		every { SQLiteDatabase.openDatabase(any(), isNull(), any()) } returns database

		val lookup = source().lookup("i/index.html")

		assertThat(lookup).isInstanceOf(DocumentationLookup.Found::class.java)
		val content = (lookup as DocumentationLookup.Found).content
		assertThat(content.bytes.toString(Charsets.UTF_8)).isEqualTo("page")
		assertThat(content.mimeType).isEqualTo("text/html")
		assertThat(content.templateId).isEqualTo(0)
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
}
