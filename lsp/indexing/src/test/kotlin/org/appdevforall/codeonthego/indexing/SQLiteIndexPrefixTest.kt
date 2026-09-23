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
 * Prefix matching on the SQLite-backed index.
 *
 * `_` and `%` are the cases that matter: `_` is a legal identifier character, so a user typing one
 * must not be handed matches that merely have some other character in that position.
 */
@RunWith(RobolectricTestRunner::class)
class SQLiteIndexPrefixTest {
	data class Entry(
		override val key: String,
		override val sourceId: String,
		val name: String,
		val pkg: String,
	) : Indexable

	private val descriptor =
		object : IndexDescriptor<Entry> {
			override val name = "test_prefix"
			override val fields =
				listOf(
					IndexField("name", prefixSearchable = true),
					IndexField("pkg"),
				)

			override fun fieldValues(entry: Entry) = mapOf("name" to entry.name, "pkg" to entry.pkg)

			override fun serialize(entry: Entry) = "${entry.key}|${entry.sourceId}|${entry.name}|${entry.pkg}".toByteArray()

			override fun deserialize(bytes: ByteArray): Entry {
				val parts = String(bytes).split("|")
				return Entry(parts[0], parts[1], parts[2], parts[3])
			}
		}

	private val index =
		SQLiteIndex(
			descriptor = descriptor,
			context = ApplicationProvider.getApplicationContext<Context>(),
			dbName = null,
			formatVersion = 1,
		)

	@After
	fun tearDown() {
		index.close()
	}

	private fun namesMatching(prefix: String) =
		index
			.query(IndexQuery(prefixMatch = mapOf("name" to prefix), limit = 0))
			.map { it.name }
			.toList()

	@Test
	fun `underscore in a prefix matches only a literal underscore`() =
		runTest {
			index.insert(Entry("k1", "s", "my_field", "p"))
			index.insert(Entry("k2", "s", "myXfield", "p"))

			assertThat(namesMatching("my_f")).containsExactly("my_field")
		}

	@Test
	fun `percent in a prefix matches only a literal percent`() =
		runTest {
			index.insert(Entry("k1", "s", "pct%value", "p"))
			index.insert(Entry("k2", "s", "pctOther", "p"))

			assertThat(namesMatching("pct%")).containsExactly("pct%value")
		}

	@Test
	fun `backslash in a prefix matches only a literal backslash`() =
		runTest {
			index.insert(Entry("k1", "s", "a\\b", "p"))
			index.insert(Entry("k2", "s", "axb", "p"))

			assertThat(namesMatching("a\\")).containsExactly("a\\b")
		}

	@Test
	fun `prefix search on a prefix-searchable field ignores case`() =
		runTest {
			index.insert(Entry("k1", "s", "ArrayList", "p"))
			index.insert(Entry("k2", "s", "arraydeque", "p"))

			assertThat(namesMatching("array")).containsExactly("ArrayList", "arraydeque")
		}

	@Test
	fun `prefix search on a plain field respects case`() =
		runTest {
			index.insert(Entry("k1", "s", "n1", "com.foo"))
			index.insert(Entry("k2", "s", "n2", "COM.BAR"))

			// Matches InMemoryIndex, which has always compared these with startsWith.
			val pkgs =
				index
					.query(IndexQuery(prefixMatch = mapOf("pkg" to "com."), limit = 0))
					.map { it.pkg }
					.toList()
			assertThat(pkgs).containsExactly("com.foo")
		}

	@Test
	fun `an empty prefix matches every entry that has the field`() =
		runTest {
			index.insert(Entry("k1", "s", "Alpha", "p"))
			index.insert(Entry("k2", "s", "Beta", "p"))

			assertThat(namesMatching("")).containsExactly("Alpha", "Beta")
		}

	@Test
	fun `a prefix ending at the highest character still matches`() =
		runTest {
			// No exclusive upper bound exists here, so only the lower bound can be emitted.
			val high = "z${Char.MAX_VALUE}"
			index.insert(Entry("k1", "s", "${high}tail", "p"))
			index.insert(Entry("k2", "s", "zzz", "p"))

			assertThat(namesMatching(high)).containsExactly("${high}tail")
		}
}
