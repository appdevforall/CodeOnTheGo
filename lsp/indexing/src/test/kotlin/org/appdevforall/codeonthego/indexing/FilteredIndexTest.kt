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
class FilteredIndexTest {

    data class Entry(
        override val key: String,
        override val sourceId: String,
        val value: String,
    ) : Indexable

    private val descriptor = object : IndexDescriptor<Entry> {
        override val name = "test_filtered"
        override val fields = listOf(IndexField("value"))

        override fun fieldValues(entry: Entry) = mapOf("value" to entry.value)

        override fun serialize(entry: Entry) =
            "${entry.key}|${entry.sourceId}|${entry.value}".toByteArray()

        override fun deserialize(bytes: ByteArray): Entry {
            val parts = String(bytes).split("|")
            return Entry(parts[0], parts[1], parts[2])
        }
    }

    private suspend fun setupBackingAndFiltered(): Pair<InMemoryIndex<Entry>, FilteredIndex<Entry>> {
        val backing = InMemoryIndex(descriptor)
        backing.insert(Entry("k1", "src1", "val1"))
        backing.insert(Entry("k2", "src1", "val2"))
        backing.insert(Entry("k3", "src2", "val3"))
        return backing to FilteredIndex(backing)
    }

    @Test
    fun `query returns nothing when no sources active`() = runTest {
        val (_, filtered) = setupBackingAndFiltered()
        assertThat(filtered.query(IndexQuery.ALL).toList()).isEmpty()
    }

    @Test
    fun `query returns entries for active source`() = runTest {
        val (_, filtered) = setupBackingAndFiltered()
        filtered.activateSource("src1")
        val keys = filtered.query(IndexQuery.ALL).map { it.key }.toList()
        assertThat(keys).containsExactly("k1", "k2")
    }

    @Test
    fun `deactivateSource hides entries from that source`() = runTest {
        val (_, filtered) = setupBackingAndFiltered()
        filtered.activateSource("src1")
        filtered.activateSource("src2")
        filtered.deactivateSource("src1")

        val keys = filtered.query(IndexQuery.ALL).map { it.key }.toList()
        assertThat(keys).containsExactly("k3")
    }

    @Test
    fun `setActiveSources replaces entire active set`() = runTest {
        val (_, filtered) = setupBackingAndFiltered()
        filtered.activateSource("src1")
        filtered.setActiveSources(setOf("src2"))

        val keys = filtered.query(IndexQuery.ALL).map { it.key }.toList()
        assertThat(keys).containsExactly("k3")
    }

    @Test
    fun `get returns null when source is inactive`() = runTest {
        val (_, filtered) = setupBackingAndFiltered()
        assertThat(filtered.get("k1")).isNull()
    }

    @Test
    fun `get returns entry when source is active`() = runTest {
        val (_, filtered) = setupBackingAndFiltered()
        filtered.activateSource("src1")
        assertThat(filtered.get("k1")).isNotNull()
        assertThat(filtered.get("k1")!!.key).isEqualTo("k1")
    }

    @Test
    fun `get returns null for nonexistent key even with active source`() = runTest {
        val (_, filtered) = setupBackingAndFiltered()
        filtered.activateSource("src1")
        assertThat(filtered.get("missing")).isNull()
    }

    @Test
    fun `containsSource returns false when source inactive`() = runTest {
        val (_, filtered) = setupBackingAndFiltered()
        assertThat(filtered.containsSource("src1")).isFalse()
    }

    @Test
    fun `containsSource returns true only when source is active AND in backing`() = runTest {
        val (_, filtered) = setupBackingAndFiltered()
        filtered.activateSource("src1")
        assertThat(filtered.containsSource("src1")).isTrue()
        assertThat(filtered.containsSource("src999")).isFalse()
    }

    @Test
    fun `isCached returns true if source exists in backing regardless of active state`() = runTest {
        val (_, filtered) = setupBackingAndFiltered()
        assertThat(filtered.isCached("src1")).isTrue()
        assertThat(filtered.isCached("src999")).isFalse()
    }

    @Test
    fun `activeSources returns current active set`() = runTest {
        val (_, filtered) = setupBackingAndFiltered()
        filtered.activateSource("src1")
        filtered.activateSource("src2")
        assertThat(filtered.activeSources()).containsExactly("src1", "src2")
    }

    @Test
    fun `isActive reflects current state`() = runTest {
        val (_, filtered) = setupBackingAndFiltered()
        assertThat(filtered.isActive("src1")).isFalse()
        filtered.activateSource("src1")
        assertThat(filtered.isActive("src1")).isTrue()
        filtered.deactivateSource("src1")
        assertThat(filtered.isActive("src1")).isFalse()
    }

    @Test
    fun `explicit sourceId query returns empty for inactive source`() = runTest {
        val (_, filtered) = setupBackingAndFiltered()
        // src1 has entries in backing but is not active
        val results = filtered.query(IndexQuery.bySource("src1")).toList()
        assertThat(results).isEmpty()
    }

    @Test
    fun `all sources active returns all entries from backing`() = runTest {
        val (_, filtered) = setupBackingAndFiltered()
        filtered.activateSource("src1")
        filtered.activateSource("src2")

        val results = filtered.query(IndexQuery.ALL).toList()
        assertThat(results).hasSize(3)
    }

    @Test
    fun `close clears active sources`() = runTest {
        val (_, filtered) = setupBackingAndFiltered()
        filtered.activateSource("src1")
        filtered.close()
        assertThat(filtered.activeSources()).isEmpty()
    }
}
