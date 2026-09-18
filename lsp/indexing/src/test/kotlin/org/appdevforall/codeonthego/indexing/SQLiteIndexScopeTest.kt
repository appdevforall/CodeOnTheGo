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

/** Source scoping and column projection on the SQLite-backed index. */
@RunWith(RobolectricTestRunner::class)
class SQLiteIndexScopeTest {
	data class Entry(
		override val key: String,
		override val sourceId: String,
		val value: String,
		val group: String = "",
	) : Indexable

	private val descriptor =
		object : IndexDescriptor<Entry> {
			override val name = "test_scope"
			override val fields =
				listOf(
					IndexField("value", prefixSearchable = true),
					IndexField("group"),
				)

			override fun fieldValues(entry: Entry) =
				mapOf(
					"value" to entry.value,
					"group" to entry.group,
				)

			override fun serialize(entry: Entry) = "${entry.key}|${entry.sourceId}|${entry.value}|${entry.group}".toByteArray()

			override fun deserialize(bytes: ByteArray): Entry {
				val parts = String(bytes).split("|")
				return Entry(parts[0], parts[1], parts[2], parts[3])
			}
		}

	private val index =
		SQLiteIndex(
			descriptor = descriptor,
			context = ApplicationProvider.getApplicationContext<Context>(),
			dbName = null,
		)

	@After
	fun tearDown() {
		index.close()
	}

	@Test
	fun `query scoped to source ids returns only those sources`() =
		runTest {
			index.insert(Entry("k1", "jarA", "Alpha"))
			index.insert(Entry("k2", "jarB", "Beta"))
			index.insert(Entry("k3", "jarC", "Gamma"))

			val keys =
				index
					.query(IndexQuery(sourceIds = listOf("jarA", "jarC"), limit = 0))
					.map { it.key }
					.toList()

			assertThat(keys).containsExactly("k1", "k3")
		}

	@Test
	fun `query scoped to an empty source set returns nothing`() =
		runTest {
			index.insert(Entry("k1", "jarA", "Alpha"))

			// Distinct from sourceIds = null, which imposes no restriction at all.
			assertThat(index.query(IndexQuery(sourceIds = emptyList(), limit = 0)).toList()).isEmpty()
			assertThat(index.query(IndexQuery(sourceIds = null, limit = 0)).toList()).hasSize(1)
		}

	@Test
	fun `query scoped to more sources than one IN clause holds returns every match`() =
		runTest {
			// Over SQLite's 999 bound-parameter limit, so the scope has to be split across statements.
			val sourceIds = (0 until 2100).map { "jar$it" }
			index.insertAll(sourceIds.asSequence().mapIndexed { i, src -> Entry("k$i", src, "Cls$i") })

			val found = index.query(IndexQuery(sourceIds = sourceIds, limit = 0)).map { it.key }.toList()

			assertThat(found).hasSize(2100)
			assertThat(found.toSet()).hasSize(2100)
		}

	@Test
	fun `scoped query applies the scope before the limit`() =
		runTest {
			repeat(20) { index.insert(Entry("noise$it", "jarNoise", "Widget$it")) }
			index.insert(Entry("wanted", "jarWanted", "Widget99"))

			val found =
				index
					.query(IndexQuery(prefixMatch = mapOf("value" to "Widget"), sourceIds = listOf("jarWanted"), limit = 1))
					.map { it.key }
					.toList()

			assertThat(found).containsExactly("wanted")
		}

	@Test
	fun `distinctValues projects only the values of matching rows`() =
		runTest {
			index.insert(Entry("k1", "jarA", "Alpha", group = "one"))
			index.insert(Entry("k2", "jarA", "Beta", group = "one"))
			index.insert(Entry("k3", "jarB", "Gamma", group = "two"))

			val groups =
				index
					.distinctValues("group", IndexQuery(sourceIds = listOf("jarA"), limit = 0))
					.toList()

			assertThat(groups).containsExactly("one")
		}

	@Test
	fun `distinctValues deduplicates across source chunks`() =
		runTest {
			// One shared value spread over more sources than a single IN clause can hold: DISTINCT is
			// per statement, so without cross-chunk deduplication this value comes back repeatedly.
			val sourceIds = (0 until 1500).map { "jar$it" }
			index.insertAll(
				sourceIds.asSequence().mapIndexed { i, src -> Entry("k$i", src, "Cls$i", group = "shared") },
			)

			val groups = index.distinctValues("group", IndexQuery(sourceIds = sourceIds, limit = 0)).toList()

			assertThat(groups).containsExactly("shared")
		}

	@Test
	fun `distinctValues without a query still returns every value`() =
		runTest {
			index.insert(Entry("k1", "jarA", "Alpha", group = "one"))
			index.insert(Entry("k2", "jarB", "Beta", group = "two"))

			assertThat(index.distinctValues("group").toList()).containsExactly("one", "two")
		}

	@Test
	fun `anyOf matches any of the listed values`() =
		runTest {
			index.insert(Entry("k1", "jarA", "Alpha", group = "one"))
			index.insert(Entry("k2", "jarA", "Beta", group = "two"))
			index.insert(Entry("k3", "jarA", "Gamma", group = "three"))

			val keys =
				index
					.query(IndexQuery(anyOf = mapOf("group" to listOf("one", "three")), limit = 0))
					.map { it.key }
					.toList()

			assertThat(keys).containsExactly("k1", "k3")
		}

	@Test
	fun `an empty anyOf matches nothing`() =
		runTest {
			index.insert(Entry("k1", "jarA", "Alpha", group = "one"))

			// Same distinction as an empty source scope: restricted to nothing, not unrestricted.
			assertThat(index.query(IndexQuery(anyOf = mapOf("group" to emptyList()), limit = 0)).toList())
				.isEmpty()
		}

	@Test
	fun `anyOf narrows the rows the limit is spent on`() =
		runTest {
			repeat(20) { index.insert(Entry("other$it", "jarA", "Cls$it", group = "unwanted")) }
			index.insert(Entry("wanted", "jarA", "ClsWanted", group = "wanted"))

			val keys =
				index
					.query(IndexQuery(anyOf = mapOf("group" to listOf("wanted")), limit = 1))
					.map { it.key }
					.toList()

			assertThat(keys).containsExactly("wanted")
		}
}
