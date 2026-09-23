package org.appdevforall.codeonthego.indexing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.appdevforall.codeonthego.indexing.api.IndexDescriptor
import org.appdevforall.codeonthego.indexing.api.IndexField
import org.appdevforall.codeonthego.indexing.api.Indexable
import org.appdevforall.codeonthego.indexing.api.indexQuery
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Which SQL index serves each query shape the JVM symbol index issues.
 *
 * Every production query is scoped to the active sources, and without statistics SQLite rates an
 * `IN` list or an equality on any indexed column as good as a range, so these pin that the
 * selective predicate drives the plan and the scope is only a filter.
 */
@RunWith(RobolectricTestRunner::class)
class SQLiteIndexQueryPlanTest {
	data class Entry(
		override val key: String,
		override val sourceId: String,
	) : Indexable

	private val descriptor =
		object : IndexDescriptor<Entry> {
			override val name = "test_plan"
			override val fields =
				listOf(
					IndexField(NAME, prefixSearchable = true),
					IndexField(PACKAGE),
					IndexField(KIND, selective = false),
					IndexField(CONTAINING_CLASS),
				)

			override fun fieldValues(entry: Entry) = emptyMap<String, String?>()

			override fun serialize(entry: Entry) = "${entry.key}|${entry.sourceId}".toByteArray()

			override fun deserialize(bytes: ByteArray): Entry {
				val parts = String(bytes).split("|")
				return Entry(parts[0], parts[1])
			}
		}

	private val context = ApplicationProvider.getApplicationContext<Context>()

	private val index = SQLiteIndex(descriptor = descriptor, context = context, dbName = DB_NAME, formatVersion = 1)

	@After
	fun tearDown() {
		index.close()
		context.deleteDatabase(DB_NAME)
	}

	@Test
	fun `a scoped name prefix query is served by the name index`() {
		val plan =
			index.explainQuery(
				indexQuery {
					prefix(NAME, "get")
					sourceIds = SOURCES
				},
			)

		assertThat(plan).contains(NAME_INDEX)
	}

	@Test
	fun `a scoped name prefix query restricted to several kinds is served by the name index`() {
		val plan =
			index.explainQuery(
				indexQuery {
					prefix(NAME, "get")
					anyOf(KIND, listOf("CLASS", "INTERFACE"))
					sourceIds = SOURCES
				},
			)

		assertThat(plan).contains(NAME_INDEX)
	}

	@Test
	fun `a scoped name prefix query restricted to one kind is served by the name index`() {
		val plan =
			index.explainQuery(
				indexQuery {
					prefix(NAME, "get")
					eq(KIND, "CLASS")
					sourceIds = SOURCES
				},
			)

		assertThat(plan).contains(NAME_INDEX)
	}

	@Test
	fun `a scoped exact name query restricted to one kind is served by the name index`() {
		val plan =
			index.explainQuery(
				indexQuery {
					eq(NAME, "Foo")
					anyOf(KIND, listOf("CLASS"))
					sourceIds = SOURCES
				},
			)

		assertThat(plan).contains("$EXACT_NAME_INDEX (")
	}

	@Test
	fun `scoped distinct packages under a prefix are served by the package index`() {
		val plan =
			index.explainDistinctValues(
				PACKAGE,
				indexQuery {
					prefix(PACKAGE, "com.example")
					sourceIds = SOURCES
					limit = 0
				},
			)

		assertThat(plan).contains(PACKAGE_INDEX)
	}

	@Test
	fun `scoped top-level callables in a package are served by the package index`() {
		val plan =
			index.explainQuery(
				indexQuery {
					eq(PACKAGE, "com.example")
					anyOf(KIND, listOf("FUNCTION", "PROPERTY"))
					notExists(CONTAINING_CLASS)
					sourceIds = SOURCES
				},
			)

		assertThat(plan).contains(PACKAGE_INDEX)
	}

	@Test
	fun `a scoped key lookup is served by the key index`() {
		val plan =
			index.explainQuery(
				indexQuery {
					key = "com.example.Foo"
					sourceIds = SOURCES
					limit = 1
				},
			)

		assertThat(plan).contains(KEY_INDEX)
	}

	@Test
	fun `a query scoped by source alone is served by the primary key`() {
		val plan = index.explainQuery(indexQuery { sourceIds = SOURCES })

		assertThat(plan).contains(PRIMARY_KEY_INDEX)
	}

	private companion object {
		const val DB_NAME = "plan_test.db"

		const val NAME = "name"
		const val PACKAGE = "package"
		const val KIND = "kind"
		const val CONTAINING_CLASS = "containingClass"

		const val NAME_INDEX = "idx_test_plan_f_name_lower"
		const val EXACT_NAME_INDEX = "idx_test_plan_f_name"
		const val PACKAGE_INDEX = "idx_test_plan_f_package"
		const val KEY_INDEX = "idx_test_plan_key"
		const val PRIMARY_KEY_INDEX = "sqlite_autoindex_test_plan_1"

		val SOURCES = (0 until 300).map { "/libs/lib$it.jar" }
	}
}
