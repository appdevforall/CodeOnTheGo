package org.appdevforall.codeonthego.indexing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.appdevforall.codeonthego.indexing.api.IndexQuery
import org.appdevforall.codeonthego.indexing.util.BackgroundIndexer
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A source's rows stay readable while a background pass re-indexes it, and after one that fails. */
@RunWith(RobolectricTestRunner::class)
class SQLiteIndexReindexVisibilityTest {
	private val context = ApplicationProvider.getApplicationContext<Context>()

	private val index =
		SQLiteIndex(
			descriptor = packagedEntryDescriptor,
			context = context,
			dbName = DB_NAME,
			formatVersion = 1,
			batchSize = 1,
		)

	private val indexer = BackgroundIndexer(index)

	@After
	fun tearDown() {
		indexer.close()
		index.close()
		context.deleteDatabase(DB_NAME)
	}

	private fun keysOf(sourceId: String) = index.query(IndexQuery.bySource(sourceId)).map { it.key }.toSet()

	private suspend fun indexJarA(
		fingerprint: String,
		entries: () -> Flow<PackagedEntry>,
	) = indexer.indexSource("jarA", skipIfExists = false, fingerprint = fingerprint) { entries() }.join()

	@Test(timeout = 30_000)
	fun `a source's previous rows stay visible while it is re-indexed`() =
		runTest {
			indexJarA("f1") { flowOf(PackagedEntry("k1", "jarA", "a.b"), PackagedEntry("k2", "jarA", "a.b")) }

			var seenMidPass = emptySet<String>()
			indexJarA("f2") {
				flow {
					emit(PackagedEntry("k1", "jarA", "a.b"))
					seenMidPass = keysOf("jarA")
					emit(PackagedEntry("k3", "jarA", "a.b"))
				}
			}

			assertThat(seenMidPass).containsAtLeast("k1", "k2")
			assertThat(keysOf("jarA")).containsExactly("k1", "k3")
		}

	@Test(timeout = 30_000)
	fun `a failed re-index keeps the previous rows and records no fingerprint`() =
		runTest {
			indexJarA("f1") { flowOf(PackagedEntry("k1", "jarA", "a.b"), PackagedEntry("k2", "jarA", "a.b")) }

			indexJarA("f2") {
				flow {
					emit(PackagedEntry("k1", "jarA", "a.b"))
					error("jar unreadable")
				}
			}

			assertThat(keysOf("jarA")).containsAtLeast("k1", "k2")
			assertThat(index.sourceFingerprint("jarA")).isNull()
		}

	@Test(timeout = 30_000)
	fun `a re-index larger than one insert batch keeps every entry`() =
		runTest {
			val entries = (0 until 1200).map { PackagedEntry("k$it", "jarA", "a.b") }
			indexJarA("f1") { flowOf(PackagedEntry("old", "jarA", "a.b")) }

			indexJarA("f2") { entries.asFlow() }

			assertThat(keysOf("jarA")).isEqualTo(entries.map { it.key }.toSet())
		}

	private companion object {
		const val DB_NAME = "reindex_visibility_test.db"
	}
}
