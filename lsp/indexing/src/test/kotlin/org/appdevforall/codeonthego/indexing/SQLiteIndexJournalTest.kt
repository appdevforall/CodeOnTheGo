package org.appdevforall.codeonthego.indexing

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.appdevforall.codeonthego.indexing.api.IndexDescriptor
import org.appdevforall.codeonthego.indexing.api.IndexField
import org.appdevforall.codeonthego.indexing.api.Indexable
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SQLiteIndexJournalTest {
	data class Entry(
		override val key: String,
		override val sourceId: String,
	) : Indexable

	private val descriptor =
		object : IndexDescriptor<Entry> {
			override val name = "test_journal"
			override val fields = emptyList<IndexField>()

			override fun fieldValues(entry: Entry) = emptyMap<String, String?>()

			override fun serialize(entry: Entry) = "${entry.key}|${entry.sourceId}".toByteArray()

			override fun deserialize(bytes: ByteArray): Entry {
				val parts = String(bytes).split("|")
				return Entry(parts[0], parts[1])
			}
		}

	private val context = ApplicationProvider.getApplicationContext<Context>()

	@After
	fun tearDown() {
		context.deleteDatabase(DB_NAME)
	}

	@Test
	fun `the database uses write-ahead logging`() {
		val index = SQLiteIndex(descriptor = descriptor, context = context, dbName = DB_NAME, formatVersion = 1)

		/*
		 * Probed over a read-only connection because a writable one would apply its own journal mode
		 * to the file on open, and WAL, once set, is what every later connection reports.
		 */
		val path = context.getDatabasePath(DB_NAME).path
		val mode =
			try {
				SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
					db.rawQuery("PRAGMA journal_mode", null).use { cursor ->
						cursor.moveToFirst()
						cursor.getString(0)
					}
				}
			} finally {
				index.close()
			}

		assertThat(mode).isEqualTo("wal")
	}

	private companion object {
		const val DB_NAME = "journal_test.db"
	}
}
