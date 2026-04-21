package org.appdevforall.codeonthego.indexing.util

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.appdevforall.codeonthego.indexing.InMemoryIndex
import org.appdevforall.codeonthego.indexing.api.IndexDescriptor
import org.appdevforall.codeonthego.indexing.api.IndexField
import org.appdevforall.codeonthego.indexing.api.Indexable
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class BackgroundIndexerTest {

    data class Entry(
        override val key: String,
        override val sourceId: String,
        val value: String,
    ) : Indexable

    private val descriptor = object : IndexDescriptor<Entry> {
        override val name = "bg_test"
        override val fields = listOf(IndexField("value"))

        override fun fieldValues(entry: Entry) = mapOf("value" to entry.value)

        override fun serialize(entry: Entry) =
            "${entry.key}|${entry.sourceId}|${entry.value}".toByteArray()

        override fun deserialize(bytes: ByteArray): Entry {
            val parts = String(bytes).split("|")
            return Entry(parts[0], parts[1], parts[2])
        }
    }

    private fun makeIndexAndIndexer(): Pair<InMemoryIndex<Entry>, BackgroundIndexer<Entry>> {
        val index = InMemoryIndex(descriptor)
        return index to BackgroundIndexer(index)
    }

    @Test
    fun `indexSource inserts all provided entries`() = runTest {
        val (index, indexer) = makeIndexAndIndexer()

        indexer.indexSource("src1", skipIfExists = false) { sourceId ->
            sequenceOf(
                Entry("k1", sourceId, "v1"),
                Entry("k2", sourceId, "v2"),
            )
        }.join()

        assertThat(index.size).isEqualTo(2)
        assertThat(index.get("k1")).isNotNull()
        assertThat(index.get("k2")).isNotNull()
    }

    @Test
    fun `skipIfExists=true skips re-indexing of existing source`() = runTest {
        val (index, indexer) = makeIndexAndIndexer()

        indexer.indexSource("src1", skipIfExists = false) { sourceId ->
            sequenceOf(Entry("k1", sourceId, "original"))
        }.join()

        indexer.indexSource("src1", skipIfExists = true) { sourceId ->
            sequenceOf(Entry("k1", sourceId, "updated"))
        }.join()

        assertThat(index.get("k1")!!.value).isEqualTo("original")
    }

    @Test
    fun `skipIfExists=false forces re-indexing`() = runTest {
        val (index, indexer) = makeIndexAndIndexer()

        indexer.indexSource("src1", skipIfExists = false) { sourceId ->
            sequenceOf(Entry("k1", sourceId, "original"))
        }.join()

        indexer.indexSource("src1", skipIfExists = false) { sourceId ->
            sequenceOf(Entry("k1", sourceId, "updated"))
        }.join()

        assertThat(index.get("k1")!!.value).isEqualTo("updated")
    }

    @Test
    fun `progressListener receives Started and Completed events`() = runTest {
        val (_, indexer) = makeIndexAndIndexer()
        val events = mutableListOf<IndexingEvent>()
        indexer.progressListener = IndexingProgressListener { _, event -> events.add(event) }

        indexer.indexSource("src1", skipIfExists = false) { sourceId ->
            sequenceOf(Entry("k1", sourceId, "v1"))
        }.join()

        assertThat(events).contains(IndexingEvent.Started)
        val completed = events.filterIsInstance<IndexingEvent.Completed>()
        assertThat(completed).hasSize(1)
        assertThat(completed.first().totalIndexed).isEqualTo(1)
    }

    @Test
    fun `progressListener receives Skipped when source already indexed`() = runTest {
        val (_, indexer) = makeIndexAndIndexer()

        indexer.indexSource("src1", skipIfExists = false) { sourceId ->
            sequenceOf(Entry("k1", sourceId, "v1"))
        }.join()

        val events = mutableListOf<IndexingEvent>()
        indexer.progressListener = IndexingProgressListener { _, event -> events.add(event) }

        indexer.indexSource("src1", skipIfExists = true) { _ ->
            emptySequence()
        }.join()

        assertThat(events).contains(IndexingEvent.Skipped)
    }

    @Test
    fun `awaitAll waits for all in-flight jobs`() = runTest {
        val (index, indexer) = makeIndexAndIndexer()

        indexer.indexSource("src1", skipIfExists = false) { sourceId ->
            sequenceOf(Entry("k1", sourceId, "v1"))
        }
        indexer.indexSource("src2", skipIfExists = false) { sourceId ->
            sequenceOf(Entry("k2", sourceId, "v2"))
        }

        indexer.awaitAll()

        assertThat(index.size).isEqualTo(2)
    }

    @Test
    fun `indexSources indexes all provided sources`() = runTest {
        val (index, indexer) = makeIndexAndIndexer()
        val sources = listOf("jar1", "jar2", "jar3")

        val jobs = indexer.indexSources(sources, skipIfExists = false) { sourceId ->
            sourceId to sequenceOf(Entry("key-$sourceId", sourceId, "val"))
        }
        jobs.forEach { it.join() }

        assertThat(index.size).isEqualTo(3)
        assertThat(index.containsSource("jar1")).isTrue()
        assertThat(index.containsSource("jar2")).isTrue()
        assertThat(index.containsSource("jar3")).isTrue()
    }

    @Test
    fun `activeJobCount reflects in-flight jobs`() = runTest {
        val (_, indexer) = makeIndexAndIndexer()
        assertThat(indexer.activeJobCount).isEqualTo(0)
    }

    @Test
    fun `close cancels all active jobs`() = runTest {
        val (_, indexer) = makeIndexAndIndexer()
        indexer.close()
        assertThat(indexer.activeJobCount).isEqualTo(0)
    }

    @Test
    fun `indexSource removes stale entries before re-indexing`() = runTest {
        val (index, indexer) = makeIndexAndIndexer()

        // Index with two entries
        indexer.indexSource("src1", skipIfExists = false) { sourceId ->
            sequenceOf(
                Entry("k1", sourceId, "old1"),
                Entry("k2", sourceId, "old2"),
            )
        }.join()

        // Re-index with only one entry
        indexer.indexSource("src1", skipIfExists = false) { sourceId ->
            sequenceOf(Entry("k1", sourceId, "new1"))
        }.join()

        assertThat(index.size).isEqualTo(1)
        assertThat(index.get("k1")!!.value).isEqualTo("new1")
        assertThat(index.get("k2")).isNull()
    }
}
