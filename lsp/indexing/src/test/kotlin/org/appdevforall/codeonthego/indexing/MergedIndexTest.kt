package org.appdevforall.codeonthego.indexing

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.appdevforall.codeonthego.indexing.api.IndexDescriptor
import org.appdevforall.codeonthego.indexing.api.IndexField
import org.appdevforall.codeonthego.indexing.api.IndexQuery
import org.appdevforall.codeonthego.indexing.api.Indexable
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class MergedIndexTest {

    data class Entry(
        override val key: String,
        override val sourceId: String,
        val value: String,
    ) : Indexable

    private val descriptor = object : IndexDescriptor<Entry> {
        override val name = "test_merged"
        override val fields = listOf(IndexField("value"))

        override fun fieldValues(entry: Entry) = mapOf("value" to entry.value)

        override fun serialize(entry: Entry) =
            "${entry.key}|${entry.sourceId}|${entry.value}".toByteArray()

        override fun deserialize(bytes: ByteArray): Entry {
            val parts = String(bytes).split("|")
            return Entry(parts[0], parts[1], parts[2])
        }
    }

    private fun makeIndex() = InMemoryIndex(descriptor)

    @Test
    fun `merges results from multiple indexes`() = runTest {
        val idx1 = makeIndex().also { it.insert(Entry("k1", "s1", "v1")) }
        val idx2 = makeIndex().also { it.insert(Entry("k2", "s2", "v2")) }
        val merged = MergedIndex(idx1, idx2)

        val keys = merged.query(IndexQuery.ALL).map { it.key }.toList()
        assertThat(keys).containsExactly("k1", "k2")
    }

    @Test
    fun `deduplicates by key - first index wins`() = runTest {
        val idx1 = makeIndex().also { it.insert(Entry("k1", "s1", "from-index1")) }
        val idx2 = makeIndex().also { it.insert(Entry("k1", "s2", "from-index2")) }
        val merged = MergedIndex(idx1, idx2)

        val results = merged.query(IndexQuery.ALL).toList()
        assertThat(results).hasSize(1)
        assertThat(results[0].value).isEqualTo("from-index1")
    }

    @Test
    fun `respects limit across indexes`() = runTest {
        val idx1 = makeIndex().also { e ->
            (1..5).forEach { i -> e.insert(Entry("k$i", "s1", "v$i")) }
        }
        val idx2 = makeIndex().also { e ->
            (6..10).forEach { i -> e.insert(Entry("k$i", "s2", "v$i")) }
        }
        val merged = MergedIndex(idx1, idx2)

        val results = merged.query(IndexQuery(limit = 3)).toList()
        assertThat(results).hasSize(3)
    }

    @Test
    fun `get returns first match in priority order`() = runTest {
        val idx1 = makeIndex().also { it.insert(Entry("k1", "s1", "primary")) }
        val idx2 = makeIndex().also { it.insert(Entry("k1", "s2", "secondary")) }
        val merged = MergedIndex(idx1, idx2)

        assertThat(merged.get("k1")!!.value).isEqualTo("primary")
    }

    @Test
    fun `get returns from second index when not in first`() = runTest {
        val idx1 = makeIndex()
        val idx2 = makeIndex().also { it.insert(Entry("k2", "s2", "second")) }
        val merged = MergedIndex(idx1, idx2)

        assertThat(merged.get("k2")!!.value).isEqualTo("second")
    }

    @Test
    fun `get returns null if key not in any index`() = runTest {
        val merged = MergedIndex(makeIndex())
        assertThat(merged.get("missing")).isNull()
    }

    @Test
    fun `containsSource checks all indexes`() = runTest {
        val idx1 = makeIndex().also { it.insert(Entry("k1", "src1", "v1")) }
        val idx2 = makeIndex().also { it.insert(Entry("k2", "src2", "v2")) }
        val merged = MergedIndex(idx1, idx2)

        assertThat(merged.containsSource("src1")).isTrue()
        assertThat(merged.containsSource("src2")).isTrue()
        assertThat(merged.containsSource("src999")).isFalse()
    }

    @Test
    fun `distinctValues deduplicates across indexes`() = runTest {
        val idx1 = makeIndex().also { e ->
            e.insert(Entry("k1", "s1", "alpha"))
            e.insert(Entry("k2", "s1", "beta"))
        }
        val idx2 = makeIndex().also { e ->
            e.insert(Entry("k3", "s2", "beta"))  // duplicate
            e.insert(Entry("k4", "s2", "gamma"))
        }
        val merged = MergedIndex(idx1, idx2)

        val values = merged.distinctValues("value").toSet()
        assertThat(values).containsExactly("alpha", "beta", "gamma")
    }

    @Test
    fun `empty merged index returns empty results`() = runTest {
        val merged = MergedIndex<Entry>(emptyList())

        assertThat(merged.query(IndexQuery.ALL).toList()).isEmpty()
        assertThat(merged.get("k1")).isNull()
        assertThat(merged.containsSource("s1")).isFalse()
        assertThat(merged.distinctValues("value").toList()).isEmpty()
    }

    @Test
    fun `single index merged works identically to unmerged`() = runTest {
        val idx = makeIndex().also {
            it.insert(Entry("k1", "s1", "val1"))
            it.insert(Entry("k2", "s1", "val2"))
        }
        val merged = MergedIndex(idx)

        assertThat(merged.query(IndexQuery.ALL).toList()).hasSize(2)
        assertThat(merged.get("k1")).isNotNull()
        assertThat(merged.containsSource("s1")).isTrue()
    }

    @Test
    fun `vararg constructor builds merged index`() = runTest {
        val idx1 = makeIndex().also { it.insert(Entry("k1", "s1", "a")) }
        val idx2 = makeIndex().also { it.insert(Entry("k2", "s2", "b")) }
        val merged = MergedIndex(idx1, idx2)

        assertThat(merged.query(IndexQuery.ALL).toList()).hasSize(2)
    }
}
