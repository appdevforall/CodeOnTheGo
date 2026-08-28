package org.appdevforall.cotg.profiler.heap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives [HeapDominators.build] over synthetic in-memory object graphs (no Shark types needed) and
 * checks the dominator-tree shape, retained sizes/counts, and pruning.
 */
class HeapDominatorsTest {
	private fun build(
		roots: List<Long>,
		refs: Map<Long, List<Long>>,
		sizes: Map<Long, Int>,
	): HeapObjectNode =
		HeapDominators.build(
			rootIds = roots,
			referencesOf = { (refs[it] ?: emptyList()).asSequence() },
			shallowSizeOf = { sizes[it] ?: 0 },
			labelOf = { "obj$it" },
		)

	@Test
	fun chainAccumulatesRetainedSizeUpward() {
		// root 1 -> 2 -> 3, each 10 bytes.
		val root =
			build(
				roots = listOf(1L),
				refs = mapOf(1L to listOf(2L), 2L to listOf(3L)),
				sizes = mapOf(1L to 10, 2L to 10, 3L to 10),
			)

		assertEquals(30L, root.retainedBytes)
		assertEquals(3L, root.retainedCount)

		val n1 = root.children.single()
		assertEquals("obj1", n1.label)
		assertEquals(30L, n1.retainedBytes)
		assertEquals(10L, n1.shallowBytes)

		val n2 = n1.children.single()
		assertEquals(20L, n2.retainedBytes)
		assertEquals(2L, n2.retainedCount)

		val n3 = n2.children.single()
		assertEquals(10L, n3.retainedBytes)
		assertTrue(n3.children.isEmpty())
	}

	@Test
	fun diamondNodeIsDominatedByCommonAncestor() {
		// root 1 -> 2, 1 -> 3, both 2 and 3 -> 4. 4 is reachable two ways, so its dominator is 1.
		val root =
			build(
				roots = listOf(1L),
				refs = mapOf(1L to listOf(2L, 3L), 2L to listOf(4L), 3L to listOf(4L)),
				sizes = mapOf(1L to 10, 2L to 10, 3L to 10, 4L to 10),
			)

		val n1 = root.children.single()
		assertEquals(40L, n1.retainedBytes)
		assertEquals(4L, n1.retainedCount)
		// 4 is lifted to be a direct child of 1 (not nested under 2 or 3).
		assertEquals(setOf("obj2", "obj3", "obj4"), n1.children.map { it.label }.toSet())
		n1.children.forEach { assertTrue(it.children.isEmpty()) }
	}

	@Test
	fun insignificantSubtreesArePrunedAndFoldIntoParentSelf() {
		// root 1 dominates one huge object and 3000 tiny ones. The tiny ones fall below both the
		// byte (0.1% of ~1MB) and count (0.1% of 3002) thresholds, so they're dropped — but their
		// bytes/count still count toward the parent's retained totals.
		val tinyIds = (2L..3001L).toList()
		val hugeId = 3002L
		val root =
			build(
				roots = listOf(1L),
				refs = mapOf(1L to (tinyIds + hugeId)),
				sizes =
					buildMap {
						put(1L, 0)
						tinyIds.forEach { put(it, 1) }
						put(hugeId, 1_000_000)
					},
			)

		val n1 = root.children.single()
		assertEquals(1_003_000L, n1.retainedBytes)
		assertEquals(3002L, n1.retainedCount)
		// Only the huge object survives pruning.
		assertEquals(1, n1.children.size)
		assertEquals(1_000_000L, n1.children.single().retainedBytes)
	}

	@Test
	fun retainedTotalsDominateChildren() {
		// The flamegraph layout relies on value >= sum(children.value); verify it for both metrics.
		val root =
			build(
				roots = listOf(1L),
				refs = mapOf(1L to listOf(2L, 3L), 2L to listOf(4L), 3L to listOf(5L)),
				sizes = mapOf(1L to 5, 2L to 10, 3L to 10, 4L to 100, 5L to 100),
			)
		assertInclusive(root)
	}

	private fun assertInclusive(node: HeapObjectNode) {
		val childBytes = node.children.sumOf { it.retainedBytes }
		val childCount = node.children.sumOf { it.retainedCount }
		assertTrue("retainedBytes >= sum children", node.retainedBytes >= childBytes)
		assertTrue("retainedCount >= sum children", node.retainedCount >= childCount)
		node.children.forEach { assertInclusive(it) }
	}
}
