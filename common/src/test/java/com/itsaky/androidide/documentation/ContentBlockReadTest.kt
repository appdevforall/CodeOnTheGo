package com.itsaky.androidide.documentation

import android.database.sqlite.SQLiteDatabase
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.File
import kotlin.random.Random

/**
 * [readContentBlock] against a real SQLite file. A block over the piece size is read a piece per
 * query, so no row has to fit Android's 2 MiB CursorWindow whole; the pieces here are a few bytes,
 * to test the piece boundaries without megabytes of data. A block over 1 MiB read through the
 * content source is in :app's ContentBlockSchemaTest, which has Brotli.
 *
 * Conscrypt off, as in DocumentationRequestInterceptorResponseTest, for SelfSignedCertTest's sake.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@ConscryptMode(ConscryptMode.Mode.OFF)
class ContentBlockReadTest {
	@get:Rule
	val folder = TemporaryFolder()

	private lateinit var database: SQLiteDatabase

	@Before
	fun setUp() {
		database = SQLiteDatabase.openOrCreateDatabase(File(folder.root, "documentation.db"), null)
		database.execSQL("CREATE TABLE ContentBlocks (id INTEGER PRIMARY KEY AUTOINCREMENT, data BLOB NOT NULL)")
	}

	@After
	fun tearDown() {
		database.close()
	}

	// Every byte value, including 0, which a TEXT read would stop at.
	@Test
	fun `a block over the piece size is read in pieces that join to its bytes`() {
		val data = ByteArray(1_000) { it.toByte() }
		block(1, data)

		val pieces = readContentBlock(database, 1, "j/x.html", pieceBytes = 300)

		assertThat(pieces.map { it.size }).containsExactly(300, 300, 300, 100).inOrder()
		assertThat(joinChunks(pieces)).isEqualTo(data)
	}

	@Test
	fun `a block of whole pieces has no empty piece after them`() {
		val data = Random(6683).nextBytes(900)
		block(1, data)

		val pieces = readContentBlock(database, 1, "j/x.html", pieceBytes = 300)

		assertThat(pieces.map { it.size }).containsExactly(300, 300, 300).inOrder()
		assertThat(joinChunks(pieces)).isEqualTo(data)
	}

	@Test
	fun `a block within the piece size is one piece`() {
		val data = Random(6683).nextBytes(300)
		block(1, data)

		assertThat(readContentBlock(database, 1, "j/x.html", pieceBytes = 300).single()).isEqualTo(data)
	}

	@Test
	fun `the default piece is the 1 MiB a continuation row holds`() {
		val data = Random(6683).nextBytes(DocumentationContentSource.CONTENT_CHUNK_SIZE + 1)
		block(1, data)

		val pieces = readContentBlock(database, 1, "j/x.html")

		assertThat(pieces.map { it.size }).containsExactly(DocumentationContentSource.CONTENT_CHUNK_SIZE, 1).inOrder()
		assertThat(joinChunks(pieces)).isEqualTo(data)
	}

	@Test
	fun `only the named block is read`() {
		block(1, ByteArray(10) { 1 })
		block(2, ByteArray(10) { 2 })

		assertThat(joinChunks(readContentBlock(database, 2, "j/x.html", pieceBytes = 4))).isEqualTo(ByteArray(10) { 2 })
	}

	@Test
	fun `a missing block fails, naming the block and the path`() {
		val e = assertThrows(IllegalStateException::class.java) { readContentBlock(database, 3, "j/x.html", pieceBytes = 4) }

		assertThat(e).hasMessageThat().isEqualTo("Content block 3 not found, for path 'j/x.html'")
	}

	private fun block(
		id: Long,
		data: ByteArray,
	) = database.execSQL("INSERT INTO ContentBlocks (id, data) VALUES (?, ?)", arrayOf<Any>(id, data))
}
