package org.appdevforall.codeonthego.indexing.util

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
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
import java.io.IOException
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
					flowOf(
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
					flowOf(Entry("k1", sourceId, "original"))
				}.join()

			indexer
				.indexSource("src1", skipIfExists = true) { sourceId ->
					flowOf(Entry("k1", sourceId, "updated"))
				}.join()

			assertThat(index.get("k1")!!.value).isEqualTo("original")
		}

	@Test
	fun `skipIfExists=false forces re-indexing`() =
		runTest {
			val (index, indexer) = makeIndexAndIndexer()

			indexer
				.indexSource("src1", skipIfExists = false) { sourceId ->
					flowOf(Entry("k1", sourceId, "original"))
				}.join()

			indexer
				.indexSource("src1", skipIfExists = false) { sourceId ->
					flowOf(Entry("k1", sourceId, "updated"))
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
					flowOf(Entry("k1", sourceId, "v1"))
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
					flowOf(Entry("k1", sourceId, "v1"))
				}.join()

			val events = mutableListOf<IndexingEvent>()
			indexer.progressListener = IndexingProgressListener { _, event -> events.add(event) }

			indexer
				.indexSource("src1", skipIfExists = true) { _ ->
					emptyFlow()
				}.join()

			assertThat(events).contains(IndexingEvent.Skipped)
		}

	@Test
	fun `awaitAll waits for all in-flight jobs`() =
		runTest {
			val (index, indexer) = makeIndexAndIndexer()

			indexer.indexSource("src1", skipIfExists = false) { sourceId ->
				flowOf(Entry("k1", sourceId, "v1"))
			}
			indexer.indexSource("src2", skipIfExists = false) { sourceId ->
				flowOf(Entry("k2", sourceId, "v2"))
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
					sourceId to flowOf(Entry("key-$sourceId", sourceId, "val"))
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
					flowOf(
						Entry("k1", sourceId, "old1"),
						Entry("k2", sourceId, "old2"),
					)
				}.join()

			// Re-index with only one entry
			indexer
				.indexSource("src1", skipIfExists = false) { sourceId ->
					flowOf(Entry("k1", sourceId, "new1"))
				}.join()

			assertThat(index.size).isEqualTo(1)
			assertThat(index.get("k1")!!.value).isEqualTo("new1")
			assertThat(index.get("k2")).isNull()
		}

	/** Stands in for a flow that holds an open JAR: [closed] flips when the flow's scope exits. */
	private class ClosingFlow(
		private val body: suspend FlowCollector<Entry>.() -> Unit,
	) {
		@Volatile var closed = false

		val flow =
			flow {
				try {
					body()
				} finally {
					closed = true
				}
			}
	}

	@Test
	fun `indexSource closes the flow when insertion fails partway`() =
		runTest {
			val index =
				object : Index<Entry> by InMemoryIndex(descriptor) {
					override suspend fun insertAll(entries: Sequence<Entry>) = error("disk full")
				}
			val indexer = BackgroundIndexer(index)
			// More entries than one batch, so insertAll fails while the flow is still open.
			val source = ClosingFlow { repeat(2000) { emit(Entry("k$it", "src1", "v")) } }

			indexer.indexSource("src1", skipIfExists = false) { source.flow }.join()

			assertThat(source.closed).isTrue()
		}

	/** Cancels the job after the first entry is collected; returns whether the source closed. */
	private suspend fun closedAfterCancelPartway(wrap: (Flow<Entry>) -> Flow<Entry>): Boolean {
		val source =
			ClosingFlow {
				emit(Entry("k1", "src1", "v1"))
				awaitCancellation()
			}
		val emitted = CompletableDeferred<Unit>()
		val job =
			BackgroundIndexer(InMemoryIndex(descriptor)).indexSource("src1", skipIfExists = false) {
				wrap(source.flow).onEach { emitted.complete(Unit) }
			}
		emitted.await()
		job.cancelAndJoin()
		return source.closed
	}

	@Test
	fun `indexSource closes the flow when the job is cancelled partway`() =
		runTest {
			assertThat(closedAfterCancelPartway { it }).isTrue()
		}

	@Test
	fun `indexSource closes a wrapped flow when the job is cancelled partway`() =
		runTest {
			assertThat(closedAfterCancelPartway { it.filter { true } }).isTrue()
		}

	@Test
	fun `entries collected before the flow fails are still inserted`() =
		runTest {
			val (index, indexer) = makeIndexAndIndexer()
			val events = mutableListOf<IndexingEvent>()
			indexer.progressListener = IndexingProgressListener { _, event -> synchronized(events) { events.add(event) } }

			indexer
				.indexSource("src1", skipIfExists = false) { sourceId ->
					flow {
						repeat(3) { emit(Entry("k$it", sourceId, "v")) }
						throw IOException("EIO closing the JAR")
					}
				}.join()

			assertThat(index.size).isEqualTo(3)
			assertThat(events.filterIsInstance<IndexingEvent.Failed>()).hasSize(1)
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

			val first = indexer.indexSource("src1", skipIfExists = false) { flowOf(Entry("k1", it, "v1")) }
			firstInserting.await()
			val second = indexer.indexSource("src1", skipIfExists = false) { flowOf(Entry("k1", it, "v1")) }
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
			// Unconfined runs the skip path to completion inside launch.
			val indexer = BackgroundIndexer(index, CoroutineScope(Dispatchers.Unconfined))

			indexer.indexSource("src1", skipIfExists = true) { emptyFlow() }.join()

			assertThat(indexer.activeJobCount).isEqualTo(0)
		}

	@Test
	fun `job cancelled before its body runs is not left tracked`() =
		runTest {
			// A cancelled parent cancels the job before its body runs, so no finally in the body executes.
			val indexer = BackgroundIndexer(InMemoryIndex(descriptor), CoroutineScope(Job().apply { cancel() }))

			indexer.indexSource("src1", skipIfExists = false) { flowOf(Entry("k1", it, "v1")) }.join()

			assertThat(indexer.activeJobCount).isEqualTo(0)
		}
}
