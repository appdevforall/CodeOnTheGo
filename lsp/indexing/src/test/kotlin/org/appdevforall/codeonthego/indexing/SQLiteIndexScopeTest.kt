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

/** Source scoping, chunked multi-statement queries, and column projection on the SQLite-backed index. */
@RunWith(RobolectricTestRunner::class)
class SQLiteIndexScopeTest {
	data class Entry(
		override val key: String,
		override val sourceId: String,
		val value: String,
		val group: String = "",
		val kind: String = "",
	) : Indexable

	private val descriptor =
		object : IndexDescriptor<Entry> {
			override val name = "test_scope"
			override val fields =
				listOf(
					IndexField("value", prefixSearchable = true),
					IndexField("group"),
					IndexField("kind", selective = false),
				)

			override fun fieldValues(entry: Entry) =
				mapOf(
					"value" to entry.value,
					"group" to entry.group,
					"kind" to entry.kind,
				)

			override fun serialize(entry: Entry) =
				listOf(entry.key, entry.sourceId, entry.value, entry.group, entry.kind).joinToString("|").toByteArray()

			override fun deserialize(bytes: ByteArray): Entry {
				val parts = String(bytes).split("|")
				return Entry(parts[0], parts[1], parts[2], parts[3], parts[4])
			}
		}

	private val index =
		SQLiteIndex(
			descriptor = descriptor,
			context = ApplicationProvider.getApplicationContext<Context>(),
			dbName = null,
			formatVersion = 1,
		)

	@After
	fun tearDown() {
		index.close()
	}

	private fun smallChunkIndex(chunkSize: Int) =
		SQLiteIndex(
			descriptor = descriptor,
			context = ApplicationProvider.getApplicationContext<Context>(),
			dbName = null,
			formatVersion = 1,
			sourceIdChunkSize = chunkSize,
		)

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
	fun `query scoped across multiple small chunks returns every match`() =
		runTest {
			/*
			 * Robolectric's native SQLite allows far more than 999 bound parameters, so the 2100-id
			 * test above passes even with chunking deleted. A small chunk size exercises the same
			 * multi-statement path on real hardware.
			 */
			smallChunkIndex(2).use { idx ->
				val sourceIds = (0 until 5).map { "jar$it" }
				idx.insertAll(sourceIds.asSequence().mapIndexed { i, src -> Entry("k$i", src, "Cls$i") })

				val found = idx.query(IndexQuery(sourceIds = sourceIds, limit = 0)).map { it.key }.toList()

				assertThat(found.toSet()).containsExactly("k0", "k1", "k2", "k3", "k4")
			}
		}

	@Test
	fun `limit is honored across chunk boundaries`() =
		runTest {
			// Three sources, one chunk each: the limit must be spent across chunks rather than per
			// chunk, and the loop must stop querying once it is met.
			smallChunkIndex(1).use { idx ->
				idx.insert(Entry("k1", "s1", "Alpha"))
				idx.insert(Entry("k2", "s1", "Alpha"))
				idx.insert(Entry("k3", "s2", "Alpha"))
				idx.insert(Entry("k4", "s2", "Alpha"))
				idx.insert(Entry("k5", "s3", "Alpha"))
				idx.insert(Entry("k6", "s3", "Alpha"))

				val found =
					idx
						.query(IndexQuery(sourceIds = listOf("s1", "s2", "s3"), limit = 3))
						.map { it.key }
						.toList()

				assertThat(found).hasSize(3)
				assertThat(found).containsAtLeast("k1", "k2")
				assertThat(found).containsNoneOf("k5", "k6")
			}
		}

	@Test
	fun `a source id repeated across chunks is not returned twice`() =
		runTest {
			smallChunkIndex(1).use { idx ->
				idx.insert(Entry("ka", "a", "Alpha"))
				idx.insert(Entry("kb", "b", "Beta"))

				val found =
					idx
						.query(IndexQuery(sourceIds = listOf("a", "a", "b"), limit = 0))
						.map { it.key }
						.toList()

				assertThat(found).containsExactly("ka", "kb")
			}
		}

	@Test
	fun `distinctValues does not starve a later chunk's new values on the shrinking limit`() =
		runTest {
			/*
			 * Old bug: each chunk was queried with `limit - values.size`, the remaining budget, so a
			 * value repeating across chunks could exhaust that budget before a later chunk's genuinely
			 * new values were ever read. Chunk one already contributes "g1"; chunk two repeats "g1"
			 * (ordered first, by source id and by value) ahead of the newly-seen "g3", so a
			 * budget-of-one second query returns the duplicate and misses "g3" entirely.
			 */
			smallChunkIndex(2).use { idx ->
				idx.insert(Entry("k1", "s1", "V1", group = "g1"))
				idx.insert(Entry("k2", "s2", "V2", group = "g2"))
				idx.insert(Entry("k3", "s3", "V3", group = "g1"))
				idx.insert(Entry("k4", "s4", "V4", group = "g3"))

				val groups =
					idx
						.distinctValues("group", IndexQuery(sourceIds = listOf("s1", "s2", "s3", "s4"), limit = 3))
						.toList()

				assertThat(groups).containsExactly("g1", "g2", "g3")
			}
		}

	@Test
	fun `distinctValues stops adding once the limit is reached mid-chunk`() =
		runTest {
			/*
			 * Old bug: querying a chunk with the full limit fixed the starvation above, but nothing
			 * capped the total afterward -- the inner cursor loop added every row a chunk returned. A
			 * chunk holding more new distinct values than remain in the budget must stop partway
			 * through, not push the total past the limit.
			 */
			smallChunkIndex(2).use { idx ->
				idx.insert(Entry("k1", "s1", "V1", group = "g1"))
				idx.insert(Entry("k2", "s2", "V2", group = "g1"))
				idx.insert(Entry("k3", "s3", "V3", group = "g2"))
				idx.insert(Entry("k4", "s4", "V4", group = "g3"))

				val groups =
					idx
						.distinctValues("group", IndexQuery(sourceIds = listOf("s1", "s2", "s3", "s4"), limit = 2))
						.toList()

				assertThat(groups).hasSize(2)
			}
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

	@Test
	fun `scope and kind terms that only filter still exclude non-matching rows`() =
		runTest {
			index.insert(Entry("match", "jarA", "Widget1", kind = "CLASS"))
			index.insert(Entry("otherKind", "jarA", "Widget2", kind = "METHOD"))
			index.insert(Entry("otherSource", "jarB", "Widget3", kind = "CLASS"))
			index.insert(Entry("otherName", "jarA", "Gadget", kind = "CLASS"))

			val byAnyOf =
				IndexQuery(
					prefixMatch = mapOf("value" to "Widget"),
					anyOf = mapOf("kind" to listOf("CLASS")),
					sourceIds = listOf("jarA"),
					limit = 0,
				)
			val byExact = byAnyOf.copy(anyOf = emptyMap(), exactMatch = mapOf("kind" to "CLASS"))

			assertThat(index.query(byAnyOf).map { it.key }.toList()).containsExactly("match")
			assertThat(index.query(byExact).map { it.key }.toList()).containsExactly("match")
		}
}
