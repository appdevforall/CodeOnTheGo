package org.appdevforall.codeonthego.indexing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.appdevforall.codeonthego.indexing.api.IndexDescriptor
import org.appdevforall.codeonthego.indexing.api.IndexField
import org.appdevforall.codeonthego.indexing.api.IndexQuery
import org.appdevforall.codeonthego.indexing.api.Indexable
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Row identity on the SQLite-backed index: a row is identified by its source and key together. */
@RunWith(RobolectricTestRunner::class)
class SQLiteIndexIdentityTest {
	data class Entry(
		override val key: String,
		override val sourceId: String,
		val value: String,
	) : Indexable

	private val descriptor =
		object : IndexDescriptor<Entry> {
			override val name = "test_identity"
			override val fields = listOf(IndexField("value", prefixSearchable = true))

			override fun fieldValues(entry: Entry) = mapOf("value" to entry.value)

			override fun serialize(entry: Entry) = "${entry.key}|${entry.sourceId}|${entry.value}".toByteArray()

			override fun deserialize(bytes: ByteArray): Entry {
				val parts = String(bytes).split("|")
				return Entry(parts[0], parts[1], parts[2])
			}
		}

	private val context = ApplicationProvider.getApplicationContext<Context>()

	private val index = SQLiteIndex(descriptor = descriptor, context = context, dbName = DB_NAME, formatVersion = 1)

	@After
	fun tearDown() {
		index.close()
		context.deleteDatabase(DB_NAME)
	}

	@Test
	fun `the same key in two sources keeps a row for each`() =
		runTest {
			index.insert(Entry("com.example.Foo", "jarA", "fromA"))
			index.insert(Entry("com.example.Foo", "jarB", "fromB"))

			assertThat(valueOfKeyIn("com.example.Foo", "jarA")).isEqualTo("fromA")
			assertThat(valueOfKeyIn("com.example.Foo", "jarB")).isEqualTo("fromB")
			assertThat(index.size()).isEqualTo(2)
		}

	@Test
	fun `re-inserting the same source and key replaces the row`() =
		runTest {
			index.insert(Entry("com.example.Foo", "jarA", "original"))
			index.insert(Entry("com.example.Foo", "jarA", "updated"))

			assertThat(valueOfKeyIn("com.example.Foo", "jarA")).isEqualTo("updated")
			assertThat(index.size()).isEqualTo(1)
		}

	@Test
	fun `get returns the row with the smallest source id when several sources share the key`() =
		runTest {
			index.insert(Entry("com.example.Foo", "jarB", "fromB"))
			index.insert(Entry("com.example.Foo", "jarA", "fromA"))
			index.insert(Entry("com.example.Foo", "jarC", "fromC"))

			assertThat(index.get("com.example.Foo")!!.sourceId).isEqualTo("jarA")
			assertThat(index.query(IndexQuery.byKey("com.example.Foo")).single().sourceId).isEqualTo("jarA")
		}

	@Test
	fun `a scoped key lookup returns the smallest source id across source chunks`() =
		runTest {
			// More sources than one IN clause holds, requested in descending order, so the smallest id
			// sits in the last chunk the caller's order would produce.
			val sourceIds = (0 until 2000).map { "jar%04d".format(it) }
			index.insertAll(sourceIds.asSequence().map { Entry("com.example.Foo", it, it) })

			val found = index.query(IndexQuery(key = "com.example.Foo", sourceIds = sourceIds.reversed(), limit = 1))

			assertThat(found.single().sourceId).isEqualTo("jar0000")
		}

	@Test
	fun `a key lookup scoped to one source ignores the same key elsewhere`() =
		runTest {
			index.insert(Entry("com.example.Foo", "jarA", "fromA"))
			index.insert(Entry("com.example.Foo", "jarB", "fromB"))

			val found = index.query(IndexQuery(key = "com.example.Foo", sourceIds = listOf("jarB"), limit = 1))

			assertThat(found.single().value).isEqualTo("fromB")
		}

	@Test
	fun `source id lookups are served by the primary key`() =
		runTest {
			index.insert(Entry("com.example.Foo", "jarA", "fromA"))

			val plan = index.explainQuery(IndexQuery.bySource("jarA"))

			assertThat(plan).contains("sqlite_autoindex_test_identity_1")
		}

	@Test
	fun `key lookups are served by an index`() =
		runTest {
			index.insert(Entry("com.example.Foo", "jarA", "fromA"))

			val plan = index.explainQuery(IndexQuery.byKey("com.example.Foo"))

			assertThat(plan).contains("USING INDEX")
			assertThat(plan).doesNotContain("SCAN")
		}

	private fun valueOfKeyIn(
		key: String,
		sourceId: String,
	) = index
		.query(IndexQuery(key = key, sourceIds = listOf(sourceId), limit = 0))
		.single()
		.value

	private companion object {
		const val DB_NAME = "identity_test.db"
	}
}
