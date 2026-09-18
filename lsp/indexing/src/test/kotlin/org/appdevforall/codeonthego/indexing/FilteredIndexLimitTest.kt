package org.appdevforall.codeonthego.indexing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.appdevforall.codeonthego.indexing.api.IndexDescriptor
import org.appdevforall.codeonthego.indexing.api.IndexField
import org.appdevforall.codeonthego.indexing.api.IndexQuery
import org.appdevforall.codeonthego.indexing.api.Indexable
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [FilteredIndex] against a real SQLite backing, where the limit is applied by the database rather
 * than by the caller.
 *
 * These use SQLite rather than [InMemoryIndex] deliberately: the defect they pin only appears when
 * the backing index applies [IndexQuery.limit] itself, which is what production does.
 */
@RunWith(RobolectricTestRunner::class)
class FilteredIndexLimitTest {
	data class Entry(
		override val key: String,
		override val sourceId: String,
		val value: String,
	) : Indexable

	private val descriptor =
		object : IndexDescriptor<Entry> {
			override val name = "test_filtered_limit"
			override val fields = listOf(IndexField("value", prefixSearchable = true))

			override fun fieldValues(entry: Entry) = mapOf("value" to entry.value)

			override fun serialize(entry: Entry) = "${entry.key}|${entry.sourceId}|${entry.value}".toByteArray()

			override fun deserialize(bytes: ByteArray): Entry {
				val parts = String(bytes).split("|")
				return Entry(parts[0], parts[1], parts[2])
			}
		}

	private val backing =
		SQLiteIndex(
			descriptor = descriptor,
			context = ApplicationProvider.getApplicationContext<Context>(),
			dbName = null,
		)

	@After
	fun tearDown() {
		backing.close()
	}

	private fun activeOnly(vararg sourceIds: String) = FilteredIndex(backing).apply { setActiveSources(sourceIds.toSet()) }

	private fun widgetQuery(limit: Int) = IndexQuery(prefixMatch = mapOf("value" to "Widget"), limit = limit)

	@Test
	fun `limited query does not lose active matches behind inactive rows`() =
		runTest {
			// Twenty matching rows from an inactive source are inserted first, so they occupy the whole
			// of a five-row SQL limit, and the one active match sits beyond them.
			repeat(20) { backing.insert(Entry("stale$it", "inactiveJar", "Widget$it")) }
			backing.insert(Entry("live", "activeJar", "WidgetLive"))

			val found = activeOnly("activeJar").query(widgetQuery(limit = 5))

			assertThat(found.map { it.key }.toList()).containsExactly("live")
		}

	@Test
	fun `limited query counts only visible rows towards the limit`() =
		runTest {
			repeat(20) { backing.insert(Entry("stale$it", "inactiveJar", "Widget$it")) }
			repeat(5) { backing.insert(Entry("live$it", "activeJar", "Widget$it")) }

			val found = activeOnly("activeJar").query(widgetQuery(limit = 3))

			assertThat(found.toList()).hasSize(3)
		}

	@Test
	fun `explicit source scope is intersected with the active set`() =
		runTest {
			backing.insert(Entry("a", "jarA", "Widget"))
			backing.insert(Entry("b", "jarB", "Widget"))

			// jarB is asked for but is not active, so it must contribute nothing.
			val found =
				activeOnly("jarA")
					.query(IndexQuery(sourceIds = listOf("jarA", "jarB"), limit = 0))
					.map { it.key }
					.toList()

			assertThat(found).containsExactly("a")
		}

	@Test
	fun `a subclass that declares every source visible is not filtered`() =
		runTest {
			backing.insert(Entry("a", "jarA", "Widget"))
			backing.insert(Entry("b", "jarB", "Widget"))

			// No source is ever activated. Overriding the scope, rather than a per-id test, is what
			// makes that mean "everything" instead of "nothing" once scoping is pushed into the query.
			val unfiltered =
				object : FilteredIndex<Entry>(backing) {
					override fun visibleSourceIds(): Collection<String>? = null
				}

			assertThat(unfiltered.query(widgetQuery(limit = 0)).map { it.key }.toList())
				.containsExactly("a", "b")
		}

	@Test
	fun `a point lookup still refuses an entry from an inactive source`() =
		runTest {
			backing.insert(Entry("a", "jarA", "Widget"))

			assertThat(activeOnly("jarA").get("a")).isNotNull()
			assertThat(activeOnly("otherJar").get("a")).isNull()
		}

	@Test
	fun `containsSource still refuses an inactive source`() =
		runTest {
			backing.insert(Entry("a", "jarA", "Widget"))

			assertThat(activeOnly("jarA").containsSource("jarA")).isTrue()
			assertThat(activeOnly("otherJar").containsSource("jarA")).isFalse()
		}

	@Test
	fun `distinctValues excludes inactive sources`() =
		runTest {
			backing.insert(Entry("a", "jarA", "Alpha"))
			backing.insert(Entry("b", "jarB", "Beta"))

			assertThat(activeOnly("jarA").distinctValues("value").toList()).containsExactly("Alpha")
		}
}
