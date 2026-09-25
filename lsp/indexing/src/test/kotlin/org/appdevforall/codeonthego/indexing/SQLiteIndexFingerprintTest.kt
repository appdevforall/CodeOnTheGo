package org.appdevforall.codeonthego.indexing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.appdevforall.codeonthego.indexing.api.IndexDescriptor
import org.appdevforall.codeonthego.indexing.api.IndexField
import org.appdevforall.codeonthego.indexing.api.Indexable
import org.appdevforall.codeonthego.indexing.util.BackgroundIndexer
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Per-source fingerprints on the SQLite-backed index, stored and dropped with the source's rows. */
@RunWith(RobolectricTestRunner::class)
class SQLiteIndexFingerprintTest {
	data class Entry(
		override val key: String,
		override val sourceId: String,
		val value: String,
	) : Indexable

	private val descriptor =
		object : IndexDescriptor<Entry> {
			override val name = "test_fingerprint"
			override val fields = listOf(IndexField("value"))

			override fun fieldValues(entry: Entry) = mapOf("value" to entry.value)

			override fun serialize(entry: Entry) = "${entry.key}|${entry.sourceId}|${entry.value}".toByteArray()

			override fun deserialize(bytes: ByteArray): Entry {
				val parts = String(bytes).split("|")
				return Entry(parts[0], parts[1], parts[2])
			}
		}

	private val context = ApplicationProvider.getApplicationContext<Context>()

	private fun open() = SQLiteIndex(descriptor = descriptor, context = context, dbName = DB_NAME, formatVersion = 1)

	private val index = open()

	@After
	fun tearDown() {
		index.close()
		context.deleteDatabase(DB_NAME)
	}

	@Test
	fun `insertSource records the fingerprint and it survives reopening`() =
		runTest {
			index.insertSource("jarA", "100:1", sequenceOf(Entry("k1", "jarA", "v")))
			index.close()

			open().use { assertThat(it.sourceFingerprint("jarA")).isEqualTo("100:1") }
		}

	@Test
	fun `a source with no entries still records its fingerprint`() =
		runTest {
			index.insertSource("jarA", "100:1", emptySequence())

			assertThat(index.sourceFingerprint("jarA")).isEqualTo("100:1")
		}

	@Test
	fun `removing a source drops its fingerprint`() =
		runTest {
			index.insertSource("jarA", "100:1", sequenceOf(Entry("k1", "jarA", "v")))
			index.insertSource("jarB", "200:2", sequenceOf(Entry("k1", "jarB", "v")))
			index.insertSource("jarC", "300:3", sequenceOf(Entry("k1", "jarC", "v")))

			index.removeBySource("jarA")
			index.removeBySources(listOf("jarB"))

			assertThat(index.sourceFingerprint("jarA")).isNull()
			assertThat(index.sourceFingerprint("jarB")).isNull()
			assertThat(index.sourceFingerprint("jarC")).isEqualTo("300:3")
		}

	@Test
	fun `clear drops every fingerprint`() =
		runTest {
			index.insertSource("jarA", "100:1", sequenceOf(Entry("k1", "jarA", "v")))

			index.clear()

			assertThat(index.sourceFingerprint("jarA")).isNull()
		}

	@Test
	fun `an insert that fails part-way records no fingerprint`() =
		runTest {
			// Past the first batch, so some rows are already committed when the source fails.
			val failing =
				sequence {
					repeat(700) { yield(Entry("k$it", "jarA", "v")) }
					throw IllegalStateException("corrupt JAR")
				}

			runCatching { index.insertSource("jarA", "100:1", failing) }

			assertThat(index.containsSource("jarA")).isTrue()
			assertThat(index.sourceFingerprint("jarA")).isNull()
		}

	@Test
	fun `a JAR rebuilt at the same path is re-indexed and an unchanged one is skipped`() =
		runTest {
			val indexer = BackgroundIndexer(index)

			indexer.indexSource("jarA", fingerprint = "100:1") { sequenceOf(Entry("k1", it, "v1")) }.join()
			indexer.indexSource("jarA", fingerprint = "100:1") { sequenceOf(Entry("k1", it, "skipped")) }.join()
			assertThat(index.get("k1")!!.value).isEqualTo("v1")

			indexer.indexSource("jarA", fingerprint = "140:2") { sequenceOf(Entry("k2", it, "v2")) }.join()
			assertThat(index.get("k1")).isNull()
			assertThat(index.get("k2")!!.value).isEqualTo("v2")
		}

	private companion object {
		const val DB_NAME = "fingerprint_test.db"
	}
}
