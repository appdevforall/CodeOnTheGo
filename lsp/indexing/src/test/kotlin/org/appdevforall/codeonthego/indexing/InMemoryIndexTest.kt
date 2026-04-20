package org.appdevforall.codeonthego.indexing

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.appdevforall.codeonthego.indexing.api.IndexDescriptor
import org.appdevforall.codeonthego.indexing.api.IndexField
import org.appdevforall.codeonthego.indexing.api.IndexQuery
import org.appdevforall.codeonthego.indexing.api.Indexable
import org.appdevforall.codeonthego.indexing.api.indexQuery
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class InMemoryIndexTest {

    data class TestEntry(
        override val key: String,
        override val sourceId: String,
        val name: String,
        val category: String? = null,
    ) : Indexable

    private val descriptor = object : IndexDescriptor<TestEntry> {
        override val name = "test"
        override val fields = listOf(
            IndexField("name", prefixSearchable = true),
            IndexField("category"),
        )

        override fun fieldValues(entry: TestEntry) = mapOf(
            "name" to entry.name,
            "category" to entry.category,
        )

        override fun serialize(entry: TestEntry) =
            "${entry.key}|${entry.sourceId}|${entry.name}|${entry.category}".toByteArray()

        override fun deserialize(bytes: ByteArray): TestEntry {
            val parts = String(bytes).split("|")
            return TestEntry(parts[0], parts[1], parts[2], parts[3].takeIf { it != "null" })
        }
    }

    private fun makeIndex() = InMemoryIndex(descriptor)

    private fun entry(key: String, sourceId: String, name: String, category: String? = null) =
        TestEntry(key, sourceId, name, category)

    @Test
    fun `insert and get by key`() = runTest {
        val index = makeIndex()
        val e = entry("k1", "src1", "Foo")
        index.insert(e)
        assertThat(index.get("k1")).isEqualTo(e)
        assertThat(index.get("missing")).isNull()
    }

    @Test
    fun `query returns all entries when no predicates`() = runTest {
        val index = makeIndex()
        index.insert(entry("k1", "src1", "Alpha"))
        index.insert(entry("k2", "src1", "Beta"))
        index.insert(entry("k3", "src2", "Gamma"))

        val results = index.query(IndexQuery.ALL).toList()
        assertThat(results).hasSize(3)
    }

    @Test
    fun `exact match on field`() = runTest {
        val index = makeIndex()
        index.insert(entry("k1", "src1", "Alpha", "lib"))
        index.insert(entry("k2", "src1", "Beta", "app"))
        index.insert(entry("k3", "src2", "Gamma", "lib"))

        val results = index.query(indexQuery { eq("category", "lib") }).toList()
        assertThat(results.map { it.key }).containsExactly("k1", "k3")
    }

    @Test
    fun `prefix match on field`() = runTest {
        val index = makeIndex()
        index.insert(entry("k1", "src1", "ArrayList"))
        index.insert(entry("k2", "src1", "ArrayDeque"))
        index.insert(entry("k3", "src1", "String"))

        val results = index.query(indexQuery { prefix("name", "Array") }).toList()
        assertThat(results.map { it.key }).containsExactlyElementsIn(listOf("k1", "k2"))
    }

    @Test
    fun `prefix match is case-insensitive`() = runTest {
        val index = makeIndex()
        index.insert(entry("k1", "src1", "ArrayList"))
        index.insert(entry("k2", "src1", "ARRAYDEQUE"))

        val results = index.query(indexQuery { prefix("name", "array") }).toList()
        assertThat(results).hasSize(2)
    }

    @Test
    fun `containsSource returns true for indexed source`() = runTest {
        val index = makeIndex()
        index.insert(entry("k1", "src1", "Foo"))
        assertThat(index.containsSource("src1")).isTrue()
        assertThat(index.containsSource("src999")).isFalse()
    }

    @Test
    fun `removeBySource removes all entries from that source`() = runTest {
        val index = makeIndex()
        index.insert(entry("k1", "src1", "Alpha"))
        index.insert(entry("k2", "src1", "Beta"))
        index.insert(entry("k3", "src2", "Gamma"))

        index.removeBySource("src1")

        assertThat(index.get("k1")).isNull()
        assertThat(index.get("k2")).isNull()
        assertThat(index.get("k3")).isNotNull()
        assertThat(index.containsSource("src1")).isFalse()
        assertThat(index.size).isEqualTo(1)
    }

    @Test
    fun `clear removes all entries`() = runTest {
        val index = makeIndex()
        index.insert(entry("k1", "src1", "Alpha"))
        index.insert(entry("k2", "src2", "Beta"))

        index.clear()

        assertThat(index.size).isEqualTo(0)
        assertThat(index.sourceCount).isEqualTo(0)
    }

    @Test
    fun `insert replaces existing entry with same key`() = runTest {
        val index = makeIndex()
        index.insert(entry("k1", "src1", "Original"))
        index.insert(entry("k1", "src1", "Updated"))

        assertThat(index.get("k1")!!.name).isEqualTo("Updated")
        assertThat(index.size).isEqualTo(1)
    }

    @Test
    fun `query by key returns single match`() = runTest {
        val index = makeIndex()
        index.insert(entry("k1", "src1", "Alpha"))
        index.insert(entry("k2", "src1", "Beta"))

        val results = index.query(IndexQuery.byKey("k1")).toList()
        assertThat(results).hasSize(1)
        assertThat(results[0].key).isEqualTo("k1")
    }

    @Test
    fun `query by source returns entries for that source`() = runTest {
        val index = makeIndex()
        index.insert(entry("k1", "src1", "Alpha"))
        index.insert(entry("k2", "src1", "Beta"))
        index.insert(entry("k3", "src2", "Gamma"))

        val results = index.query(IndexQuery.bySource("src1")).toList()
        assertThat(results.map { it.key }).containsExactly("k1", "k2")
    }

    @Test
    fun `query respects limit`() = runTest {
        val index = makeIndex()
        repeat(10) { i -> index.insert(entry("k$i", "src1", "Name$i")) }

        val results = index.query(IndexQuery(limit = 3)).toList()
        assertThat(results).hasSize(3)
    }

    @Test
    fun `presence filter - field exists`() = runTest {
        val index = makeIndex()
        index.insert(entry("k1", "src1", "Alpha", "lib"))
        index.insert(entry("k2", "src1", "Beta")) // no category

        val results = index.query(indexQuery { exists("category") }).toList()
        assertThat(results.map { it.key }).containsExactly("k1")
    }

    @Test
    fun `presence filter - field absent`() = runTest {
        val index = makeIndex()
        index.insert(entry("k1", "src1", "Alpha", "lib"))
        index.insert(entry("k2", "src1", "Beta"))

        val results = index.query(indexQuery { notExists("category") }).toList()
        assertThat(results.map { it.key }).containsExactly("k2")
    }

    @Test
    fun `distinctValues returns unique field values`() = runTest {
        val index = makeIndex()
        index.insert(entry("k1", "src1", "Alpha", "lib"))
        index.insert(entry("k2", "src1", "Beta", "lib"))
        index.insert(entry("k3", "src2", "Gamma", "app"))

        val values = index.distinctValues("category").toSet()
        assertThat(values).containsExactly("lib", "app")
    }

    @Test
    fun `multi-field combined query`() = runTest {
        val index = makeIndex()
        index.insert(entry("k1", "src1", "ArrayList", "java"))
        index.insert(entry("k2", "src1", "ArrayDeque", "java"))
        index.insert(entry("k3", "src2", "ArrayBlockingQueue", "concurrent"))

        val results = index.query(indexQuery {
            eq("category", "java")
            prefix("name", "Array")
        }).toList()
        assertThat(results.map { it.key }).containsExactlyElementsIn(listOf("k1", "k2"))
    }

    @Test
    fun `insertAll inserts multiple entries`() = runTest {
        val index = makeIndex()
        val entries = (1..5).map { i -> entry("k$i", "src1", "Name$i") }
        index.insertAll(entries.asSequence())

        assertThat(index.size).isEqualTo(5)
    }

    @Test
    fun `query with unknown field returns empty sequence`() = runTest {
        val index = makeIndex()
        index.insert(entry("k1", "src1", "Foo"))

        val results = index.query(indexQuery { eq("nonexistentField", "value") }).toList()
        assertThat(results).isEmpty()
    }

    @Test
    fun `removeBySource is no-op for unknown source`() = runTest {
        val index = makeIndex()
        index.insert(entry("k1", "src1", "Foo"))
        index.removeBySource("unknownSource")
        assertThat(index.size).isEqualTo(1)
    }
}
