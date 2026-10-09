package org.appdevforall.codeonthego.indexing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Package tree storage details only the SQLite-backed index has: chunked scopes, plans and rebuilds. */
@RunWith(RobolectricTestRunner::class)
class SQLiteIndexPackageTreeTest {
	private val context = ApplicationProvider.getApplicationContext<Context>()

	@After
	fun tearDown() {
		context.deleteDatabase(DB_NAME)
	}

	private fun open(
		formatVersion: Int = 1,
		dbName: String? = null,
	) = SQLiteIndex(
		descriptor = packagedEntryDescriptor,
		context = context,
		dbName = dbName,
		formatVersion = formatVersion,
		sourceIdChunkSize = 2,
	)

	@Test
	fun `a scope spanning several chunks sees every chunk's packages once`() =
		runTest {
			open().use { index ->
				val sources = (0 until 5).map { "jar$it" }
				for (source in sources) {
					index.insert(PackagedEntry("shared", source, "a.shared"))
					index.insert(PackagedEntry("own", source, "a.$source"))
				}

				val children = index.subpackages("a", sourceIds = sources)

				assertThat(children).containsExactly("a.shared", "a.jar0", "a.jar1", "a.jar2", "a.jar3", "a.jar4")
				assertThat(index.containsPackage("a.jar4", sourceIds = sources)).isTrue()
			}
		}

	@Test
	fun `a scoped subpackage lookup is served by the parent index`() {
		open().use { index ->
			val plan = index.explainSubpackages("a", sourceIds = listOf("jarA", "jarB"))

			assertThat(plan).contains("idx_test_packages_packages_parent")
		}
	}

	@Test
	fun `an unscoped subpackage lookup is served by the parent index`() {
		open().use { index ->
			val plan = index.explainSubpackages("a", sourceIds = null)

			assertThat(plan).contains("idx_test_packages_packages_parent")
		}
	}

	@Test
	fun `an unscoped package existence check is served by the parent index`() {
		open().use { index ->
			val plan = index.explainContainsPackage("a.b", sourceIds = null)

			assertThat(plan).contains("idx_test_packages_packages_parent")
		}
	}

	@Test
	fun `reopening at a newer version drops the packages`() =
		runTest {
			open(formatVersion = 1, dbName = DB_NAME).use { it.insert(PackagedEntry("k1", "jarA", "a.b")) }

			open(formatVersion = 2, dbName = DB_NAME).use {
				assertThat(it.subpackages("", sourceIds = null)).isEmpty()
			}
		}

	@Test
	fun `the in-memory index lists the same packages`() =
		runTest {
			val entries =
				listOf(
					PackagedEntry("k1", "jarA", "android.app"),
					PackagedEntry("k2", "jarA", "android.view.inputmethod"),
					PackagedEntry("k3", "jarB", "android.view"),
					PackagedEntry("k4", "jarB", "androidx.core.app"),
					PackagedEntry("k5", "jarC", "kotlin"),
					PackagedEntry("k6", "jarC", ""),
				)
			val parents = listOf("", "android", "android.view", "androidx", "androidx.core", "kotlin", "missing")
			val scopes = listOf(null, emptyList(), listOf("jarA"), listOf("jarB", "jarC"), listOf("jarA", "jarB", "jarC"))

			open().use { sqlite ->
				val memory = InMemoryIndex(packagedEntryDescriptor)
				sqlite.insertAll(entries.asSequence())
				memory.insertAll(entries.asSequence())

				for (scope in scopes) {
					for (parent in parents) {
						assertThat(sqlite.subpackages(parent, scope)).isEqualTo(memory.subpackages(parent, scope))
						assertThat(sqlite.containsPackage(parent, scope)).isEqualTo(memory.containsPackage(parent, scope))
					}
				}
				assertThat(sqlite.subpackages("android", sourceIds = null)).containsExactly("android.app", "android.view")
			}
		}

	private companion object {
		const val DB_NAME = "package_tree_test.db"
	}
}
