package org.appdevforall.codeonthego.indexing

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.appdevforall.codeonthego.indexing.api.IndexDescriptor
import org.appdevforall.codeonthego.indexing.api.IndexField
import org.appdevforall.codeonthego.indexing.api.Indexable
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The stored format version decides whether an existing database file's rows are kept. */
@RunWith(RobolectricTestRunner::class)
class SQLiteIndexVersionTest {
	data class Entry(
		override val key: String,
		override val sourceId: String,
		val value: String,
	) : Indexable

	private val descriptor =
		object : IndexDescriptor<Entry> {
			override val name = "test_version"
			override val fields = listOf(IndexField("value"))

			override fun fieldValues(entry: Entry) = mapOf("value" to entry.value)

			override fun serialize(entry: Entry) = "${entry.key}|${entry.sourceId}|${entry.value}".toByteArray()

			override fun deserialize(bytes: ByteArray): Entry {
				val parts = String(bytes).split("|")
				return Entry(parts[0], parts[1], parts[2])
			}
		}

	private val context = ApplicationProvider.getApplicationContext<Context>()

	@After
	fun tearDown() {
		context.deleteDatabase(DB_NAME)
	}

	private fun open(version: Int) = SQLiteIndex(descriptor = descriptor, context = context, dbName = DB_NAME, formatVersion = version)

	@Test
	fun `reopening at the same version keeps the rows`() =
		runTest {
			open(version = 1).use { it.insert(Entry("k1", "jarA", "v")) }

			open(version = 1).use { assertThat(it.containsSource("jarA")).isTrue() }
		}

	@Test
	fun `reopening at a newer version drops the old rows`() =
		runTest {
			open(version = 1).use { it.insert(Entry("k1", "jarA", "v")) }

			open(version = 2).use {
				assertThat(it.containsSource("jarA")).isFalse()
				assertThat(it.size()).isEqualTo(0)
			}
		}

	@Test
	fun `reopening at an older version drops the newer rows`() =
		runTest {
			open(version = 2).use { it.insert(Entry("k1", "jarA", "v")) }

			open(version = 1).use { assertThat(it.size()).isEqualTo(0) }
		}

	@Test
	fun `a version change replaces a table created with the single-column key`() =
		runTest {
			val path = context.getDatabasePath(DB_NAME)
			path.parentFile!!.mkdirs()
			SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
				db.execSQL(
					"CREATE TABLE test_version (_key TEXT PRIMARY KEY, _source_id TEXT NOT NULL, f_value TEXT, _payload BLOB NOT NULL)",
				)
				db.version = 1
			}

			open(version = 2).use {
				it.insert(Entry("k1", "jarA", "fromA"))
				it.insert(Entry("k1", "jarB", "fromB"))

				assertThat(it.size()).isEqualTo(2)
			}
		}

	private companion object {
		const val DB_NAME = "version_test.db"
	}
}
