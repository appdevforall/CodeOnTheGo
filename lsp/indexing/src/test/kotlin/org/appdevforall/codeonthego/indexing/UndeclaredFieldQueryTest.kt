package org.appdevforall.codeonthego.indexing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.appdevforall.codeonthego.indexing.api.Index
import org.appdevforall.codeonthego.indexing.api.IndexDescriptor
import org.appdevforall.codeonthego.indexing.api.IndexField
import org.appdevforall.codeonthego.indexing.api.IndexQuery
import org.appdevforall.codeonthego.indexing.api.Indexable
import org.appdevforall.codeonthego.indexing.api.indexQuery
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A query naming a field the descriptor does not declare is a caller bug, which SQLite used to hide
 * by dropping the predicate and returning every row in scope. InMemoryIndexTest covers the in-memory
 * index, which rejects it the same way.
 */
@RunWith(RobolectricTestRunner::class)
class UndeclaredFieldQueryTest {
	data class Entry(
		override val key: String,
		override val sourceId: String,
		val kind: String,
	) : Indexable

	private val descriptor =
		object : IndexDescriptor<Entry> {
			override val name = "test_undeclared"
			override val fields = listOf(IndexField("kind"))

			override fun fieldValues(entry: Entry) = mapOf("kind" to entry.kind)

			override fun serialize(entry: Entry) = listOf(entry.key, entry.sourceId, entry.kind).joinToString("|").toByteArray()

			override fun deserialize(bytes: ByteArray): Entry {
				val parts = String(bytes).split("|")
				return Entry(parts[0], parts[1], parts[2])
			}
		}

	private val sqlite =
		SQLiteIndex(
			descriptor = descriptor,
			context = ApplicationProvider.getApplicationContext<Context>(),
			dbName = null,
			formatVersion = 1,
		)

	@After
	fun tearDown() {
		sqlite.close()
	}

	private fun assertRejects(
		index: Index<Entry>,
		query: IndexQuery,
	) = runTest {
		index.insert(Entry("k1", "jar", "CLASS"))
		assertThrows(IllegalArgumentException::class.java) { index.query(query).toList() }
	}

	@Test
	fun `SQLite rejects an anyOf on an undeclared field`() = assertRejects(sqlite, indexQuery { anyOf("kinds", listOf("CLASS")) })

	@Test
	fun `SQLite rejects an exact match on an undeclared field`() = assertRejects(sqlite, indexQuery { eq("kinds", "CLASS") })

	@Test
	fun `SQLite rejects a presence check on an undeclared field`() = assertRejects(sqlite, indexQuery { notExists("kinds") })
}
