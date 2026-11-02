package org.appdevforall.codeonthego.indexing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.logging.provider.IdeLogRouter
import kotlinx.coroutines.runBlocking
import org.appdevforall.codeonthego.indexing.api.IndexDescriptor
import org.appdevforall.codeonthego.indexing.api.IndexField
import org.appdevforall.codeonthego.indexing.api.IndexQuery
import org.appdevforall.codeonthego.indexing.api.Indexable
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.slf4j.event.Level
import java.util.Collections

@RunWith(RobolectricTestRunner::class)
class SQLiteIndexUseAfterCloseTest {
	data class Entry(
		override val key: String,
		override val sourceId: String,
	) : Indexable

	private data class LogEvent(
		val level: Level,
		val message: String,
		val throwable: Throwable?,
	)

	private val descriptor =
		object : IndexDescriptor<Entry> {
			override val name = "test_use_after_close"
			override val fields = emptyList<IndexField>()

			override fun fieldValues(entry: Entry) = emptyMap<String, String?>()

			override fun serialize(entry: Entry) = "${entry.key}|${entry.sourceId}".toByteArray()

			override fun deserialize(bytes: ByteArray): Entry {
				val parts = String(bytes).split("|")
				return Entry(parts[0], parts[1])
			}
		}

	private val context = ApplicationProvider.getApplicationContext<Context>()
	private val captured: MutableList<LogEvent> = Collections.synchronizedList(mutableListOf())
	private val sink =
		IdeLogRouter.ExternalSink { level, _, message, throwable ->
			captured += LogEvent(level, message, throwable)
		}

	@Before
	fun installSink() {
		IdeLogRouter.addSink(sink)
	}

	@After
	fun tearDown() {
		IdeLogRouter.removeSink(sink)
		context.deleteDatabase(DB_NAME)
	}

	@Test
	fun `reading a closed index logs one error naming the index`(): Unit =
		runBlocking {
			val index = newIndex()
			index.close()

			index.query(IndexQuery(sourceIds = null, limit = 0)).toList()
			index.get("k")
			index.insert(Entry("k", "s"))

			val errors = errorsNamingIndex()
			assertThat(errors).hasSize(1)
			assertThat(errors.single().message).contains("query")
			assertThat(errors.single().throwable).isNotNull()
		}

	@Test
	fun `an open index logs nothing`(): Unit =
		runBlocking {
			val index = newIndex()
			try {
				index.query(IndexQuery(sourceIds = null, limit = 0)).toList()
				index.get("k")
				index.insert(Entry("k", "s"))
			} finally {
				index.close()
			}

			assertThat(errorsNamingIndex()).isEmpty()
		}

	@Test
	fun `use after close in an ended session logs one debug and no error`(): Unit =
		runBlocking {
			val index = newIndex(IndexSessionLifetime().apply { end() })
			index.close()

			index.query(IndexQuery(sourceIds = null, limit = 0)).toList()
			index.insert(Entry("k", "s"))

			assertThat(errorsNamingIndex()).isEmpty()
			assertThat(captured.filter { it.level == Level.DEBUG && INDEX_NAME in it.message }).hasSize(1)
		}

	@Test
	fun `use after close in a live session still logs one error`(): Unit =
		runBlocking {
			val index = newIndex(IndexSessionLifetime())
			index.close()

			index.query(IndexQuery(sourceIds = null, limit = 0)).toList()
			index.insert(Entry("k", "s"))

			assertThat(errorsNamingIndex()).hasSize(1)
		}

	private fun newIndex(lifetime: IndexSessionLifetime? = null) =
		SQLiteIndex(
			descriptor = descriptor,
			context = context,
			dbName = DB_NAME,
			formatVersion = 1,
			name = INDEX_NAME,
			lifetime = lifetime,
		)

	private fun errorsNamingIndex() = captured.filter { it.level == Level.ERROR && INDEX_NAME in it.message }

	private companion object {
		const val DB_NAME = "use_after_close_test.db"
		const val INDEX_NAME = "use-after-close-probe"
	}
}
