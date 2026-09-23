package org.appdevforall.codeonthego.indexing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.appdevforall.codeonthego.indexing.api.Index
import org.appdevforall.codeonthego.indexing.api.IndexDescriptor
import org.appdevforall.codeonthego.indexing.api.IndexField
import org.appdevforall.codeonthego.indexing.api.Indexable
import org.appdevforall.codeonthego.indexing.api.PackageTree
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner

/** The package tree an index derives from its entries, pinned identically for both index implementations. */
@RunWith(ParameterizedRobolectricTestRunner::class)
class PackageTreeTest(
	private val implementation: String,
) {
	private val index: PackageTreeIndex = openPackageTreeIndex(implementation)

	@After
	fun tearDown() {
		index.close()
	}

	@Test
	fun `an entry's package makes every ancestor package exist`() =
		runTest {
			index.insert(PackagedEntry("k1", "jarA", "a.b.c"))

			assertThat(index.containsPackage("a.b.c", sourceIds = null)).isTrue()
			assertThat(index.containsPackage("a.b", sourceIds = null)).isTrue()
			assertThat(index.containsPackage("a", sourceIds = null)).isTrue()
			assertThat(index.containsPackage("a.b.c.d", sourceIds = null)).isFalse()
		}

	@Test
	fun `subpackages of the empty parent are the root packages`() =
		runTest {
			index.insert(PackagedEntry("k1", "jarA", "a.b.c"))
			index.insert(PackagedEntry("k2", "jarA", "x.y"))

			assertThat(index.subpackages("", sourceIds = null)).containsExactly("a", "x")
		}

	@Test
	fun `subpackages are the direct children only`() =
		runTest {
			index.insert(PackagedEntry("k1", "jarA", "a.b.c"))

			assertThat(index.subpackages("a", sourceIds = null)).containsExactly("a.b")
			assertThat(index.subpackages("a.b", sourceIds = null)).containsExactly("a.b.c")
			assertThat(index.subpackages("a.b.c", sourceIds = null)).isEmpty()
		}

	@Test
	fun `a package in two sources is listed once`() =
		runTest {
			index.insert(PackagedEntry("k1", "jarA", "a.b"))
			index.insert(PackagedEntry("k2", "jarB", "a.b"))
			index.insert(PackagedEntry("k3", "jarB", "a.c"))

			assertThat(index.subpackages("a", sourceIds = null)).containsExactly("a.b", "a.c")
		}

	@Test
	fun `a scoped lookup excludes packages only another source has`() =
		runTest {
			index.insert(PackagedEntry("k1", "jarA", "a.b"))
			index.insert(PackagedEntry("k2", "jarB", "a.c"))

			assertThat(index.subpackages("a", sourceIds = listOf("jarA"))).containsExactly("a.b")
			assertThat(index.containsPackage("a.c", sourceIds = listOf("jarA"))).isFalse()
			assertThat(index.containsPackage("a.c", sourceIds = listOf("jarA", "jarB"))).isTrue()
		}

	@Test
	fun `a lookup scoped to no sources finds nothing`() =
		runTest {
			index.insert(PackagedEntry("k1", "jarA", "a.b"))

			assertThat(index.subpackages("", sourceIds = emptyList())).isEmpty()
			assertThat(index.containsPackage("a", sourceIds = emptyList())).isFalse()
		}

	@Test
	fun `the default package has no package entry`() =
		runTest {
			index.insert(PackagedEntry("k1", "jarA", ""))
			index.insert(PackagedEntry("k2", "jarA", null))

			assertThat(index.subpackages("", sourceIds = null)).isEmpty()
			assertThat(index.containsPackage("", sourceIds = null)).isFalse()
		}

	@Test
	fun `removing a source removes the packages only it had`() =
		runTest {
			index.insert(PackagedEntry("k1", "jarA", "a.b"))
			index.insert(PackagedEntry("k2", "jarA", "x"))
			index.insert(PackagedEntry("k3", "jarB", "a.b"))

			index.removeBySource("jarA")

			assertThat(index.subpackages("", sourceIds = null)).containsExactly("a")
			assertThat(index.containsPackage("a.b", sourceIds = null)).isTrue()
			assertThat(index.containsPackage("a.b", sourceIds = listOf("jarA"))).isFalse()
			assertThat(index.containsPackage("x", sourceIds = null)).isFalse()
		}

	@Test
	fun `removing several sources removes their packages`() =
		runTest {
			index.insert(PackagedEntry("k1", "jarA", "a"))
			index.insert(PackagedEntry("k2", "jarB", "b"))
			index.insert(PackagedEntry("k3", "jarC", "c"))

			index.removeBySources(listOf("jarA", "jarB"))

			assertThat(index.subpackages("", sourceIds = null)).containsExactly("c")
		}

	@Test
	fun `clearing the index removes every package`() =
		runTest {
			index.insert(PackagedEntry("k1", "jarA", "a.b"))

			index.clear()

			assertThat(index.subpackages("", sourceIds = null)).isEmpty()
		}

	@Test
	fun `inserting a source records its packages`() =
		runTest {
			index.insertSource(
				"jarA",
				fingerprint = "f1",
				entries = sequenceOf(PackagedEntry("k1", "jarA", "a.b"), PackagedEntry("k2", "jarA", "a.c")),
			)

			assertThat(index.subpackages("a", sourceIds = listOf("jarA"))).containsExactly("a.b", "a.c")
		}

	companion object {
		@JvmStatic
		@ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
		fun implementations() = listOf(arrayOf<Any>(SQLITE), arrayOf<Any>(MEMORY))
	}
}

/** An index whose package tree tests can query directly. */
internal interface PackageTreeIndex :
	Index<PackagedEntry>,
	PackageTree

internal const val SQLITE = "sqlite"
internal const val MEMORY = "memory"

internal data class PackagedEntry(
	override val key: String,
	override val sourceId: String,
	val pkg: String?,
) : Indexable

internal val packagedEntryDescriptor =
	object : IndexDescriptor<PackagedEntry> {
		override val name = "test_packages"
		override val fields = listOf(IndexField("pkg"))

		override fun fieldValues(entry: PackagedEntry) = mapOf("pkg" to entry.pkg)

		override fun packageOf(entry: PackagedEntry) = entry.pkg

		override fun serialize(entry: PackagedEntry) = "${entry.key}|${entry.sourceId}|${entry.pkg.orEmpty()}".toByteArray()

		override fun deserialize(bytes: ByteArray): PackagedEntry {
			val parts = String(bytes).split("|")
			return PackagedEntry(parts[0], parts[1], parts[2])
		}
	}

internal fun openPackageTreeIndex(implementation: String): PackageTreeIndex =
	when (implementation) {
		SQLITE -> {
			val sqlite =
				SQLiteIndex(
					descriptor = packagedEntryDescriptor,
					context = ApplicationProvider.getApplicationContext<Context>(),
					dbName = null,
					formatVersion = 1,
				)
			object : PackageTreeIndex, Index<PackagedEntry> by sqlite, PackageTree by sqlite {}
		}

		MEMORY -> {
			val memory = InMemoryIndex(packagedEntryDescriptor)
			object : PackageTreeIndex, Index<PackagedEntry> by memory, PackageTree by memory {}
		}

		else -> {
			error("Unknown implementation: $implementation")
		}
	}
