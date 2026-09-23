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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
	fun `activeJobCount drops to zero once a completed job's entry is removed`() =
		runTest {
			val (_, indexer) = makeIndexAndIndexer()

			indexer
				.indexSource("src1", skipIfExists = false) { sourceId ->
					sequenceOf(Entry("k1", sourceId, "v1"))
				}.join()

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

	@Test
	fun `an unchanged fingerprint skips re-indexing`() =
		runTest {
			val (index, indexer) = makeIndexAndIndexer()

			indexer.indexSource("src1", fingerprint = "100:1") { sequenceOf(Entry("k1", it, "original")) }.join()
			indexer.indexSource("src1", fingerprint = "100:1") { sequenceOf(Entry("k1", it, "updated")) }.join()

			assertThat(index.get("k1")!!.value).isEqualTo("original")
		}

	@Test
	fun `a changed fingerprint re-indexes the source`() =
		runTest {
			val (index, indexer) = makeIndexAndIndexer()

			indexer.indexSource("src1", fingerprint = "100:1") { sequenceOf(Entry("k1", it, "original")) }.join()
			indexer.indexSource("src1", fingerprint = "120:2") { sequenceOf(Entry("k1", it, "rebuilt")) }.join()

			assertThat(index.get("k1")!!.value).isEqualTo("rebuilt")
			assertThat(index.sourceFingerprint("src1")).isEqualTo("120:2")
		}

	@Test
	fun `a fingerprint re-indexes a source that was indexed without one`() =
		runTest {
			val (index, indexer) = makeIndexAndIndexer()

			indexer.indexSource("src1") { sequenceOf(Entry("k1", it, "original")) }.join()
			indexer.indexSource("src1", fingerprint = "100:1") { sequenceOf(Entry("k1", it, "fingerprinted")) }.join()

			assertThat(index.get("k1")!!.value).isEqualTo("fingerprinted")
		}

	/** Waits for the latch, failing the test (rather than silently proceeding) on timeout. */
	private fun CountDownLatch.awaitOrFail() {
		assertThat(await(5, TimeUnit.SECONDS)).isTrue()
	}

	@Test
	fun `a new job waits for the previous job to finish before deleting its rows`() =
		runTest {
			val (index, indexer) = makeIndexAndIndexer()

			val job1Started = CountDownLatch(1)
			val releaseJob1 = CountDownLatch(1)

			val job1 =
				indexer.indexSource("src1", fingerprint = "100:1") { sourceId ->
					job1Started.countDown()
					releaseJob1.awaitOrFail()
					// Long enough that, without cancelAndJoin, job2's in-memory write below finishes first.
					Thread.sleep(50)
					sequenceOf(Entry("k1", sourceId, "old1"), Entry("k2", sourceId, "old2"))
				}

			job1Started.awaitOrFail()

			val job2 =
				indexer.indexSource("src1", fingerprint = "200:2") { sourceId ->
					sequenceOf(Entry("k3", sourceId, "new1"))
				}

			releaseJob1.countDown()
			job2.join()
			job1.join()

			assertThat(index.get("k1")).isNull()
			assertThat(index.get("k2")).isNull()
			assertThat(index.get("k3")!!.value).isEqualTo("new1")
			assertThat(index.sourceFingerprint("src1")).isEqualTo("200:2")
		}

	@Test
	fun `indexSource skips resubmitting a source with an active job at the same fingerprint`() =
		runTest {
			val (index, indexer) = makeIndexAndIndexer()

			val job1Started = CountDownLatch(1)
			val releaseJob1 = CountDownLatch(1)
			var providerInvocations = 0

			val job1 =
				indexer.indexSource("src1", fingerprint = "100:1") { sourceId ->
					providerInvocations++
					job1Started.countDown()
					releaseJob1.awaitOrFail()
					sequenceOf(Entry("k1", sourceId, "v1"))
				}

			job1Started.awaitOrFail()

			val job2 =
				indexer.indexSource("src1", fingerprint = "100:1") { sourceId ->
					providerInvocations++
					sequenceOf(Entry("k1", sourceId, "should-not-run"))
				}

			assertThat(job2).isSameInstanceAs(job1)

			releaseJob1.countDown()
			job1.join()

			assertThat(providerInvocations).isEqualTo(1)
			assertThat(index.get("k1")!!.value).isEqualTo("v1")
		}

	@Test
	fun `a superseded job finishing does not clear the new job's active-job entry`() =
		runTest {
			val (index, indexer) = makeIndexAndIndexer()

			val releaseJob1 = CountDownLatch(1)
			val job2ProviderStarted = CountDownLatch(1)
			val releaseJob2 = CountDownLatch(1)

			val job1 =
				indexer.indexSource("src1", fingerprint = "100:1") { sourceId ->
					releaseJob1.awaitOrFail()
					sequenceOf(Entry("k1", sourceId, "old"))
				}

			var job2ProviderInvocations = 0
			val job2 =
				indexer.indexSource("src1", fingerprint = "200:2") { sourceId ->
					job2ProviderInvocations++
					job2ProviderStarted.countDown()
					releaseJob2.awaitOrFail()
					sequenceOf(Entry("k2", sourceId, "new"))
				}

			// Let the superseded job1 finish (its cancellation is cooperative-only here, since
			// InMemoryIndex never checks it) while job2 is still mid-flight.
			releaseJob1.countDown()
			job1.join()
			job2ProviderStarted.awaitOrFail()

			var job3ProviderInvocations = 0
			val job3 =
				indexer.indexSource("src1", fingerprint = "200:2") { sourceId ->
					job3ProviderInvocations++
					sequenceOf(Entry("k2", sourceId, "should-not-run"))
				}

			assertThat(job3).isSameInstanceAs(job2)
			assertThat(job3ProviderInvocations).isEqualTo(0)

			releaseJob2.countDown()
			job2.join()

			assertThat(job2ProviderInvocations).isEqualTo(1)
			assertThat(index.get("k2")!!.value).isEqualTo("new")
		}
}
