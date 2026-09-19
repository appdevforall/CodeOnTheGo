/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.tooling.impl.serial

import com.android.builder.model.v2.ide.ArtifactDependencies
import com.android.builder.model.v2.ide.GraphItem
import com.android.builder.model.v2.ide.UnresolvedDependency
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.project.AndroidModels
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Covers the flattening of AGP's nested [GraphItem] tree into the interned, index-addressed
 * [AndroidModels.DependencyGraph] that [ArtifactDependencies.asProtoModel] emits.
 *
 * AGP repeats a shared node once per path through the graph, so the serialized form used to grow
 * with the number of *paths* rather than the number of *dependencies* (786,554 nodes and 1,011,910
 * key strings for 57,517 distinct dependencies on an 86-module project). These tests pin the two
 * properties that make the flattened form lossless: every distinct key becomes exactly one node,
 * and the edges still describe the same graph.
 */
@RunWith(JUnit4::class)
class DependencyGraphFlatteningTest {
	private class FakeGraphItem(
		override var key: String,
		override var requestedCoordinates: String? = null,
		override var dependencies: List<GraphItem> = emptyList(),
	) : GraphItem

	private class FakeArtifactDependencies(
		override val compileDependencies: List<GraphItem>,
	) : ArtifactDependencies {
		override val runtimeDependencies: List<GraphItem> = emptyList()
		override val unresolvedDependencies: List<UnresolvedDependency> = emptyList()
	}

	private fun graphOf(vararg roots: GraphItem): AndroidModels.DependencyGraph =
		FakeArtifactDependencies(roots.toList()).asProtoModel().compileGraph

	/** The key of [graph]'s node at [index], resolved through the graph's interned key table. */
	private fun keyOf(
		graph: AndroidModels.DependencyGraph,
		index: Int,
	): String = graph.getKey(graph.getNode(index).keyId)

	/** The keys [graph]'s node at [index] depends on, in declaration order. */
	private fun dependencyKeysOf(
		graph: AndroidModels.DependencyGraph,
		index: Int,
	): List<String> = graph.getNode(index).dependencyList.map { keyOf(graph, it) }

	/**
	 * The keys the node named [key] depends on. Node indices follow the depth-first walk order, which
	 * is an implementation detail, so tests that care about edges address nodes by key instead.
	 */
	private fun dependencyKeysOf(
		graph: AndroidModels.DependencyGraph,
		key: String,
	): List<String> = dependencyKeysOf(graph, (0 until graph.nodeCount).single { keyOf(graph, it) == key })

	@Test
	fun `a node shared by two paths is emitted once and referenced twice`() {
		// a -> b -> d, a -> c -> d. AGP emits `d` twice; the flattened graph must emit it once.
		val d = FakeGraphItem("d")
		val a =
			FakeGraphItem(
				"a",
				dependencies =
					listOf(
						FakeGraphItem("b", dependencies = listOf(d)),
						FakeGraphItem("c", dependencies = listOf(d)),
					),
			)

		val graph = graphOf(a)

		assertThat(graph.nodeCount).isEqualTo(4)
		assertThat((0 until graph.nodeCount).map { keyOf(graph, it) }).containsExactly("a", "b", "c", "d")

		val b = graph.getNode(graph.getNode(0).getDependency(0))
		val c = graph.getNode(graph.getNode(0).getDependency(1))
		assertThat(b.getDependency(0)).isEqualTo(c.getDependency(0))
	}

	@Test
	fun `each distinct key is interned exactly once`() {
		// The same shared node reached from two separate roots must not duplicate its key string.
		val shared = FakeGraphItem("shared")
		val graph =
			graphOf(
				FakeGraphItem("a", dependencies = listOf(shared)),
				FakeGraphItem("b", dependencies = listOf(shared)),
			)

		assertThat(graph.keyList).containsExactly("a", "shared", "b")
		assertThat(graph.keyList).containsNoDuplicates()
	}

	@Test
	fun `edges are preserved`() {
		val graph =
			graphOf(
				FakeGraphItem(
					"a",
					dependencies =
						listOf(
							FakeGraphItem("b", dependencies = listOf(FakeGraphItem("d"))),
							FakeGraphItem("c", dependencies = listOf(FakeGraphItem("d"))),
						),
				),
			)

		assertThat(dependencyKeysOf(graph, "a")).containsExactly("b", "c").inOrder()
		assertThat(dependencyKeysOf(graph, "b")).containsExactly("d")
		assertThat(dependencyKeysOf(graph, "c")).containsExactly("d")
		assertThat(dependencyKeysOf(graph, "d")).isEmpty()
	}

	@Test
	fun `roots are recorded in order`() {
		val graph = graphOf(FakeGraphItem("a"), FakeGraphItem("b"), FakeGraphItem("c"))

		assertThat(graph.rootList.map { keyOf(graph, it) }).containsExactly("a", "b", "c").inOrder()
	}

	@Test
	fun `a root reached as a transitive dependency of an earlier root is not duplicated`() {
		// `b` is both a root and a dependency of `a`. It must resolve to the same node either way.
		val b = FakeGraphItem("b")
		val graph = graphOf(FakeGraphItem("a", dependencies = listOf(b)), b)

		assertThat(graph.nodeCount).isEqualTo(2)
		assertThat(graph.rootList).containsExactly(0, 1).inOrder()
		assertThat(graph.getNode(0).getDependency(0)).isEqualTo(graph.rootList[1])
	}

	@Test(timeout = 10_000)
	fun `a cyclic graph terminates and keeps both edges`() {
		// The node index is reserved before its children are walked, so a -> b -> a terminates
		// instead of recursing until the stack overflows.
		val a = FakeGraphItem("a")
		val b = FakeGraphItem("b", dependencies = listOf(a))
		a.dependencies = listOf(b)

		val graph = graphOf(a)

		assertThat(graph.nodeCount).isEqualTo(2)
		assertThat(dependencyKeysOf(graph, "a")).containsExactly("b")
		assertThat(dependencyKeysOf(graph, "b")).containsExactly("a")
	}

	@Test(timeout = 10_000)
	fun `a self dependency terminates`() {
		val a = FakeGraphItem("a")
		a.dependencies = listOf(a)

		val graph = graphOf(a)

		assertThat(graph.nodeCount).isEqualTo(1)
		assertThat(dependencyKeysOf(graph, "a")).containsExactly("a")
	}

	@Test
	fun `requested coordinates are interned and optional`() {
		val graph =
			graphOf(
				FakeGraphItem("a", requestedCoordinates = "g:a:1.0"),
				FakeGraphItem("b", requestedCoordinates = "g:a:1.0"),
				FakeGraphItem("c"),
			)

		assertThat(graph.requestedCoordinatesList).containsExactly("g:a:1.0")
		assertThat(graph.getNode(0).hasRequestedCoordinatesId()).isTrue()
		assertThat(graph.getNode(1).requestedCoordinatesId).isEqualTo(graph.getNode(0).requestedCoordinatesId)
		assertThat(graph.getNode(2).hasRequestedCoordinatesId()).isFalse()
	}

	@Test
	fun `an empty dependency list yields an empty graph`() {
		val graph = graphOf()

		assertThat(graph.nodeCount).isEqualTo(0)
		assertThat(graph.keyList).isEmpty()
		assertThat(graph.rootList).isEmpty()
	}
}
