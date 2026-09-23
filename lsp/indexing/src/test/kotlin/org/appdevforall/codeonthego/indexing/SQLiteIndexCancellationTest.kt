package org.appdevforall.codeonthego.indexing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.appdevforall.codeonthego.indexing.api.IndexDescriptor
import org.appdevforall.codeonthego.indexing.api.IndexField
import org.appdevforall.codeonthego.indexing.api.Indexable
import org.appdevforall.codeonthego.indexing.util.BackgroundIndexer
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A cancelled indexing job must stop committing batches rather than run its scan to completion. */
@RunWith(RobolectricTestRunner::class)
class SQLiteIndexCancellationTest {
	data class Entry(
		override val key: String,
		override val sourceId: String,
		val value: String,
	) : Indexable

	private val descriptor =
		object : IndexDescriptor<Entry> {
			override val name = "test_cancellation"
			override val fields = listOf(IndexField("value"))

			override fun fieldValues(entry: Entry) = mapOf("value" to entry.value)

			override fun serialize(entry: Entry) = "${entry.key}|${entry.sourceId}|${entry.value}".toByteArray()

			override fun deserialize(bytes: ByteArray): Entry {
				val parts = String(bytes).split("|")
				return Entry(parts[0], parts[1], parts[2])
			}
		}

	private val context = ApplicationProvider.getApplicationContext<Context>()

	/** batchSize = 1 so every entry is its own transaction, isolating the per-batch check. */
	private val index =
		SQLiteIndex(descriptor = descriptor, context = context, dbName = DB_NAME, formatVersion = 1, batchSize = 1)

	@After
	fun tearDown() {
		index.close()
		context.deleteDatabase(DB_NAME)
	}

	private fun CountDownLatch.awaitOrFail() {
		assertThat(await(5, TimeUnit.SECONDS)).isTrue()
	}

	@Test
	fun `cancelling a job stops it from committing batches produced after cancellation`() =
		runTest {
			val indexer = BackgroundIndexer(index)
			val secondEntryReached = CountDownLatch(1)
			val proceedWithSecondEntry = CountDownLatch(1)

			val job =
				indexer.indexSource("jarA", skipIfExists = false, fingerprint = "1:1") { sourceId ->
					sequence {
						yield(Entry("k1", sourceId, "v1"))
						secondEntryReached.countDown()
						proceedWithSecondEntry.awaitOrFail()
						yield(Entry("k2", sourceId, "v2"))
					}
				}

			// The first batch (batchSize = 1) has already committed by the time the sequence
			// asks to produce its second entry.
			secondEntryReached.awaitOrFail()

			job.cancel()
			proceedWithSecondEntry.countDown()
			job.cancelAndJoin()

			assertThat(index.get("k1")!!.value).isEqualTo("v1")
			assertThat(index.get("k2")).isNull()
			// The fingerprint is only ever written alongside the source's last batch; a cancelled
			// job must not record one, or a later call would wrongly treat "jarA" as up to date.
			assertThat(index.sourceFingerprint("jarA")).isNull()
		}

	private companion object {
		const val DB_NAME = "cancellation_test.db"
	}
}
