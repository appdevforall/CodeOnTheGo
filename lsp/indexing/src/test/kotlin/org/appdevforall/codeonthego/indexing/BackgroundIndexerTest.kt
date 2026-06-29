package org.appdevforall.codeonthego.indexing

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.appdevforall.codeonthego.indexing.api.IndexDescriptor
import org.appdevforall.codeonthego.indexing.api.IndexField
import org.appdevforall.codeonthego.indexing.api.IndexQuery
import org.appdevforall.codeonthego.indexing.api.Indexable
import org.appdevforall.codeonthego.indexing.util.BackgroundIndexer
import org.appdevforall.codeonthego.indexing.util.IndexingEvent
import org.appdevforall.codeonthego.indexing.util.IndexingProgressListener
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.util.concurrent.CopyOnWriteArrayList

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
        override fun fieldValues(e: Entry) = mapOf("value" to e.value)
        override fun serialize(e: Entry) = "${e.key}|${e.sourceId}|${e.value}".toByteArray()
        override fun deserialize(bytes: ByteArray): Entry {
            val p = String(bytes).split("|")
            return Entry(p[0], p[1], p[2])
        }
    }

    private lateinit var index: InMemoryIndex<Entry>
    private lateinit var indexer: BackgroundIndexer<Entry>
    private val scope = CoroutineScope(Dispatchers.Default)

    @Before
    fun setUp() {
        index = InMemoryIndex(descriptor)
        indexer = BackgroundIndexer(index, scope)
    }

    @After
    fun tearDown() {
        indexer.close()
    }

    private fun entry(key: String, src: String, value: String) = Entry(key, src, value)

    // --- Basic indexing ---

    @Test
    fun `indexSource inserts entries into backing index`() = runBlocking {
        val job = indexer.indexSource("src1") { sourceId ->
            sequenceOf(entry("k1", sourceId, "v1"), entry("k2", sourceId, "v2"))
        }
        job.join()

        assertThat(index.size).isEqualTo(2)
        assertThat(index.get("k1")).isNotNull()
        assertThat(index.get("k2")).isNotNull()
    }

    @Test
    fun `indexSource skips already-indexed source when skipIfExists is true`() = runBlocking {
        // Pre-populate the index so containsSource returns true
        index.insert(entry("k0", "src1", "existing"))

        var providerCalled = false
        val job = indexer.indexSource("src1", skipIfExists = true) { sourceId ->
            providerCalled = true
            sequenceOf(entry("k1", sourceId, "v1"))
        }
        job.join()

        assertThat(providerCalled).isFalse()
        assertThat(index.size).isEqualTo(1) // original entry intact
    }

    @Test
    fun `indexSource re-indexes source when skipIfExists is false`() = runBlocking {
        index.insert(entry("k0", "src1", "old"))

        val job = indexer.indexSource("src1", skipIfExists = false) { sourceId ->
            sequenceOf(entry("k1", sourceId, "new"))
        }
        job.join()

        assertThat(index.get("k0")).isNull()   // old entry removed
        assertThat(index.get("k1")).isNotNull() // new entry added
    }

    // --- Progress listener ---

    @Test
    fun `progress listener receives Started and Completed events`() = runBlocking {
        val events = CopyOnWriteArrayList<IndexingEvent>()
        indexer.progressListener = IndexingProgressListener { _, event -> events.add(event) }

        val job = indexer.indexSource("src1") { sourceId ->
            sequenceOf(entry("k1", sourceId, "v1"))
        }
        job.join()

        assertThat(events).contains(IndexingEvent.Started)
        assertThat(events.filterIsInstance<IndexingEvent.Completed>()).isNotEmpty()
    }

    @Test
    fun `progress listener Completed event carries total count`() = runBlocking {
        var completedCount = -1
        indexer.progressListener = IndexingProgressListener { _, event ->
            if (event is IndexingEvent.Completed) completedCount = event.totalIndexed
        }

        val job = indexer.indexSource("src1") { sourceId ->
            (1..5).asSequence().map { i -> entry("k$i", sourceId, "v$i") }
        }
        job.join()

        assertThat(completedCount).isEqualTo(5)
    }

    @Test
    fun `progress listener receives Skipped when source already indexed`() = runBlocking {
        index.insert(entry("k0", "src1", "existing"))

        val events = CopyOnWriteArrayList<IndexingEvent>()
        indexer.progressListener = IndexingProgressListener { _, event -> events.add(event) }

        val job = indexer.indexSource("src1", skipIfExists = true) { sequenceOf() }
        job.join()

        assertThat(events).contains(IndexingEvent.Skipped)
    }

    @Test
    fun `progress listener receives Failed event on provider exception`() = runBlocking {
        val events = CopyOnWriteArrayList<IndexingEvent>()
        indexer.progressListener = IndexingProgressListener { _, event -> events.add(event) }

        val job = indexer.indexSource("src-err") { _ ->
            sequence { throw RuntimeException("provider blew up") }
        }
        job.join()

        assertThat(events.filterIsInstance<IndexingEvent.Failed>()).isNotEmpty()
    }

    // --- Multiple sources ---

    @Test
    fun `indexSources indexes all provided sources`() = runBlocking {
        val sources = listOf("jar1", "jar2", "jar3")

        val jobs = indexer.indexSources(sources) { src ->
            src to sequenceOf(entry("$src-k1", src, "v1"))
        }
        jobs.forEach { it.join() }

        assertThat(index.size).isEqualTo(3)
        sources.forEach { src ->
            assertThat(index.query(IndexQuery.bySource(src)).toList()).hasSize(1)
        }
    }

    // --- Active job count ---

    @Test
    fun `activeJobCount reflects running jobs`() = runBlocking {
        var started = false
        val job = indexer.indexSource("slow-src") { sourceId ->
            sequence {
                started = true
                Thread.sleep(200)
                yield(entry("k1", sourceId, "v"))
            }
        }

        // Wait until the provider is running
        while (!started) delay(10)
        assertThat(indexer.activeJobCount).isGreaterThan(0)

        job.join()
        assertThat(indexer.activeJobCount).isEqualTo(0)
    }

    // --- awaitAll ---

    @Test
    fun `awaitAll suspends until all active jobs complete`() = runBlocking {
        indexer.indexSource("s1") { src -> (1..3).asSequence().map { entry("s1-k$it", src, "v") } }
        indexer.indexSource("s2") { src -> (1..3).asSequence().map { entry("s2-k$it", src, "v") } }

        indexer.awaitAll()

        assertThat(index.size).isEqualTo(6)
    }
}
