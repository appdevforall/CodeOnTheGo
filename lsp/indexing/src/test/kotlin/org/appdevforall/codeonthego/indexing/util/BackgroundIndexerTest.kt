package org.appdevforall.codeonthego.indexing.util

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.appdevforall.codeonthego.indexing.InMemoryIndex
import org.appdevforall.codeonthego.indexing.api.Index
import org.appdevforall.codeonthego.indexing.api.IndexDescriptor
import org.appdevforall.codeonthego.indexing.api.IndexField
import org.appdevforall.codeonthego.indexing.api.Indexable
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.util.concurrent.atomic.AtomicInteger

@RunWith(JUnit4::class)
class BackgroundIndexerTest {
	data class Entry(
		override val key: String,
		override val sourceId: String,
		val value: String,
	) : Indexable

	private val descriptor =
		object : IndexDescriptor<Entry> {
			override val name = "bg_test"
			override val fields = listOf(IndexField("value"))

			override fun fieldValues(entry: Entry) = mapOf("value" to entry.value)

			override fun serialize(entry: Entry) = "${entry.key}|${entry.sourceId}|${entry.value}".toByteArray()

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
	fun `indexSource inserts all provided entries`() =
		runTest {
			val (index, indexer) = makeIndexAndIndexer()

			indexer
				.indexSource("src1", skipIfExists = false) { sourceId ->
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
	fun `skipIfExists=true skips re-indexing of existing source`() =
		runTest {
			val (index, indexer) = makeIndexAndIndexer()

			indexer
				.indexSource("src1", skipIfExists = false) { sourceId ->
					sequenceOf(Entry("k1", sourceId, "original"))
				}.join()

			indexer
				.indexSource("src1", skipIfExists = true) { sourceId ->
					sequenceOf(Entry("k1", sourceId, "updated"))
				}.join()

			assertThat(index.get("k1")!!.value).isEqualTo("original")
		}

	@Test
	fun `skipIfExists=false forces re-indexing`() =
		runTest {
			val (index, indexer) = makeIndexAndIndexer()

			indexer
				.indexSource("src1", skipIfExists = false) { sourceId ->
					sequenceOf(Entry("k1", sourceId, "original"))
				}.join()

			indexer
				.indexSource("src1", skipIfExists = false) { sourceId ->
					sequenceOf(Entry("k1", sourceId, "updated"))
				}.join()

			assertThat(index.get("k1")!!.value).isEqualTo("updated")
		}

	@Test
	fun `progressListener receives Started and Completed events`() =
		runTest {
			val (_, indexer) = makeIndexAndIndexer()
			val events = mutableListOf<IndexingEvent>()
			indexer.progressListener = IndexingProgressListener { _, event -> events.add(event) }

			indexer
				.indexSource("src1", skipIfExists = false) { sourceId ->
					sequenceOf(Entry("k1", sourceId, "v1"))
				}.join()

			assertThat(events).contains(IndexingEvent.Started)
			val completed = events.filterIsInstance<IndexingEvent.Completed>()
			assertThat(completed).hasSize(1)
			assertThat(completed.first().totalIndexed).isEqualTo(1)
		}

	@Test
	fun `progressListener receives Skipped when source already indexed`() =
		runTest {
			val (_, indexer) = makeIndexAndIndexer()

			indexer
				.indexSource("src1", skipIfExists = false) { sourceId ->
					sequenceOf(Entry("k1", sourceId, "v1"))
				}.join()

			val events = mutableListOf<IndexingEvent>()
			indexer.progressListener = IndexingProgressListener { _, event -> events.add(event) }

			indexer
				.indexSource("src1", skipIfExists = true) { _ ->
					emptySequence()
				}.join()

			assertThat(events).contains(IndexingEvent.Skipped)
		}

	@Test
	fun `awaitAll waits for all in-flight jobs`() =
		runTest {
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
	fun `indexSources indexes all provided sources`() =
		runTest {
			val (index, indexer) = makeIndexAndIndexer()
			val sources = listOf("jar1", "jar2", "jar3")

			val jobs =
				indexer.indexSources(sources, skipIfExists = false) { sourceId ->
					sourceId to sequenceOf(Entry("key-$sourceId", sourceId, "val"))
				}
			jobs.forEach { it.join() }

			assertThat(index.size).isEqualTo(3)
			assertThat(index.containsSource("jar1")).isTrue()
			assertThat(index.containsSource("jar2")).isTrue()
			assertThat(index.containsSource("jar3")).isTrue()
		}

	@Test
	fun `activeJobCount reflects in-flight jobs`() =
		runTest {
			val (_, indexer) = makeIndexAndIndexer()
			assertThat(indexer.activeJobCount).isEqualTo(0)
		}

	@Test
	fun `close cancels all active jobs`() =
		runTest {
			val (_, indexer) = makeIndexAndIndexer()
			indexer.close()
			assertThat(indexer.activeJobCount).isEqualTo(0)
		}

	@Test
	fun `indexSource removes stale entries before re-indexing`() =
		runTest {
			val (index, indexer) = makeIndexAndIndexer()

			// Index with two entries
			indexer
				.indexSource("src1", skipIfExists = false) { sourceId ->
					sequenceOf(
						Entry("k1", sourceId, "old1"),
						Entry("k2", sourceId, "old2"),
					)
				}.join()

			// Re-index with only one entry
			indexer
				.indexSource("src1", skipIfExists = false) { sourceId ->
					sequenceOf(Entry("k1", sourceId, "new1"))
				}.join()

			assertThat(index.size).isEqualTo(1)
			assertThat(index.get("k1")!!.value).isEqualTo("new1")
			assertThat(index.get("k2")).isNull()
		}

	/** Stands in for a sequence that holds an open JAR. */
	private class CloseableEntries(
		private val entries: List<Entry>,
	) : Sequence<Entry>,
		AutoCloseable {
		@Volatile var closed = false

		override fun iterator() = entries.iterator()

		override fun close() {
			closed = true
		}
	}

	/** An index whose insertAll consumes one entry, then runs [afterFirst] and abandons the rest. */
	private fun indexAbandoningAfterFirst(afterFirst: suspend () -> Unit): Index<Entry> =
		object : Index<Entry> by InMemoryIndex(descriptor) {
			override suspend fun insertAll(entries: Sequence<Entry>) {
				entries.first()
				afterFirst()
			}
		}

	private fun twoEntries(sourceId: String) = CloseableEntries(listOf(Entry("k1", sourceId, "v1"), Entry("k2", sourceId, "v2")))

	@Test
	fun `indexSource closes the entries when insertion fails partway`() =
		runTest {
			val indexer = BackgroundIndexer(indexAbandoningAfterFirst { error("disk full") })
			lateinit var entries: CloseableEntries

			indexer
				.indexSource("src1", skipIfExists = false) { sourceId ->
					twoEntries(sourceId).also { entries = it }
				}.join()

			assertThat(entries.closed).isTrue()
		}

	@Test
	fun `indexSource closes the entries when the job is cancelled partway`() =
		runTest {
			val inserting = CompletableDeferred<Unit>()
			val indexer =
				BackgroundIndexer(
					indexAbandoningAfterFirst {
						inserting.complete(Unit)
						awaitCancellation()
					},
				)
			lateinit var entries: CloseableEntries

			val job =
				indexer.indexSource("src1", skipIfExists = false) { sourceId ->
					twoEntries(sourceId).also { entries = it }
				}
			inserting.await()
			job.cancelAndJoin()

			assertThat(entries.closed).isTrue()
		}

	@Test
	fun `cancelled job does not untrack the replacement job for the same source`() =
		runTest {
			val firstInserting = CompletableDeferred<Unit>()
			val secondRegistered = CompletableDeferred<Unit>()
			val releaseSecond = CompletableDeferred<Unit>()
			val calls = AtomicInteger()
			val index =
				object : Index<Entry> by InMemoryIndex(descriptor) {
					override suspend fun insertAll(entries: Sequence<Entry>) {
						if (calls.incrementAndGet() == 1) {
							firstInserting.complete(Unit)
							try {
								awaitCancellation()
							} finally {
								// Hold the first job's cleanup until the replacement is registered.
								withContext(NonCancellable) { secondRegistered.await() }
							}
						} else {
							releaseSecond.await()
						}
					}
				}
			val indexer = BackgroundIndexer(index)

			val first = indexer.indexSource("src1", skipIfExists = false) { emptySequence() }
			firstInserting.await()
			val second = indexer.indexSource("src1", skipIfExists = false) { emptySequence() }
			secondRegistered.complete(Unit)
			first.join()

			assertThat(indexer.activeJobCount).isEqualTo(1)

			releaseSecond.complete(Unit)
			second.join()
			assertThat(indexer.activeJobCount).isEqualTo(0)
		}

	@Test
	fun `job that completes before indexSource returns is not left tracked`() =
		runTest {
			val index = InMemoryIndex(descriptor)
			index.insert(Entry("k1", "src1", "v1"))
			// Unconfined runs the skip path to completion inside launch/start.
			val indexer = BackgroundIndexer(index, CoroutineScope(Dispatchers.Unconfined))

			indexer.indexSource("src1", skipIfExists = true) { emptySequence() }.join()

			assertThat(indexer.activeJobCount).isEqualTo(0)
		}
}
