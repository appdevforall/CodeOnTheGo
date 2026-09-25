package org.appdevforall.codeonthego.indexing

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.appdevforall.codeonthego.indexing.api.IndexQuery
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner

/** Re-inserting a source replaces its entries, pinned identically for both index implementations. */
@RunWith(ParameterizedRobolectricTestRunner::class)
class IndexReplaceSourceTest(
	private val implementation: String,
) {
	private val index: PackageTreeIndex = openPackageTreeIndex(implementation)

	@After
	fun tearDown() {
		index.close()
	}

	private fun keysOf(sourceId: String) = index.query(IndexQuery.bySource(sourceId)).map { it.key }.toSet()

	@Test
	fun `inserting a source again replaces its previous entries`() =
		runTest {
			index.insertSource("jarA", "f1", sequenceOf(PackagedEntry("k1", "jarA", "a.old"), PackagedEntry("k2", "jarA", "a.old")))

			index.insertSource("jarA", "f2", sequenceOf(PackagedEntry("k1", "jarA", "a.new"), PackagedEntry("k3", "jarA", "a.new")))

			assertThat(keysOf("jarA")).containsExactly("k1", "k3")
			assertThat(index.sourceFingerprint("jarA")).isEqualTo("f2")
		}

	@Test
	fun `inserting a source again drops the packages only its previous entries had`() =
		runTest {
			index.insertSource("jarA", "f1", sequenceOf(PackagedEntry("k1", "jarA", "a.old")))

			index.insertSource("jarA", "f2", sequenceOf(PackagedEntry("k1", "jarA", "a.new")))

			assertThat(index.subpackages("a", sourceIds = listOf("jarA"))).containsExactly("a.new")
		}

	@Test
	fun `inserting a source again leaves other sources alone`() =
		runTest {
			index.insertSource("jarA", "f1", sequenceOf(PackagedEntry("k1", "jarA", "a.b")))
			index.insertSource("jarB", "f1", sequenceOf(PackagedEntry("k1", "jarB", "a.b")))

			index.insertSource("jarA", "f2", emptySequence())

			assertThat(keysOf("jarA")).isEmpty()
			assertThat(keysOf("jarB")).containsExactly("k1")
			assertThat(index.subpackages("a", sourceIds = listOf("jarB"))).containsExactly("a.b")
		}

	companion object {
		@JvmStatic
		@ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
		fun implementations() = listOf(arrayOf<Any>(SQLITE), arrayOf<Any>(MEMORY))
	}
}
