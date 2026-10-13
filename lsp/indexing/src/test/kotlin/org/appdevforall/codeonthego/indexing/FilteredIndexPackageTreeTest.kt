package org.appdevforall.codeonthego.indexing

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.appdevforall.codeonthego.indexing.api.ReadableIndex
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** A filtered view's package tree admits only the packages of its visible sources. */
@RunWith(JUnit4::class)
class FilteredIndexPackageTreeTest {
	private val backing = InMemoryIndex(packagedEntryDescriptor)

	@Before
	fun setUp() =
		runTest {
			backing.insert(PackagedEntry("k1", "jarA", "a.fromA"))
			backing.insert(PackagedEntry("k2", "jarB", "a.fromB"))
			backing.insert(PackagedEntry("k3", "jarC", "a.fromC"))
		}

	private fun activeOnly(vararg sourceIds: String) = FilteredIndex(backing).apply { setActiveSources(sourceIds.toSet()) }

	@Test
	fun `an unscoped lookup sees only the active sources' packages`() {
		val filtered = activeOnly("jarA", "jarB")

		assertThat(filtered.subpackages("a", sourceIds = null)).containsExactly("a.fromA", "a.fromB")
		assertThat(filtered.containsPackage("a.fromC", sourceIds = null)).isFalse()
	}

	@Test
	fun `a scoped lookup sees only the requested sources that are active`() {
		val filtered = activeOnly("jarA", "jarB")

		assertThat(filtered.subpackages("a", sourceIds = listOf("jarB", "jarC"))).containsExactly("a.fromB")
		assertThat(filtered.containsPackage("a.fromC", sourceIds = listOf("jarC"))).isFalse()
	}

	@Test
	fun `no active source means no packages`() {
		val filtered = FilteredIndex(backing)

		assertThat(filtered.subpackages("", sourceIds = null)).isEmpty()
		assertThat(filtered.containsPackage("a", sourceIds = null)).isFalse()
	}

	@Test
	fun `a subclass that declares every source visible passes the requested scope through`() {
		val unfiltered =
			object : FilteredIndex<PackagedEntry>(backing) {
				override fun visibleSourceIds(): Collection<String>? = null
			}

		assertThat(unfiltered.subpackages("a", sourceIds = null)).containsExactly("a.fromA", "a.fromB", "a.fromC")
		assertThat(unfiltered.subpackages("a", sourceIds = listOf("jarC"))).containsExactly("a.fromC")
	}

	@Test
	fun `a backing index without a package tree has no packages`() {
		val withoutTree = object : ReadableIndex<PackagedEntry> by backing {}
		val filtered = FilteredIndex(withoutTree).apply { setActiveSources(setOf("jarA")) }

		assertThat(filtered.subpackages("a", sourceIds = null)).isEmpty()
		assertThat(filtered.containsPackage("a", sourceIds = null)).isFalse()
	}
}
