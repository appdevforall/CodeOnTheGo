package org.appdevforall.cotg.profiler.heap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeapTreeFilterTest {
	private fun node(
		label: String,
		retained: Long = 0,
		shallow: Long = 0,
		count: Long = 0,
		children: List<HeapObjectNode> = emptyList(),
	) = HeapObjectNode(label, shallow, retained, count, children)

	private val tree =
		node(
			"(heap)",
			retained = 100,
			count = 5,
			children =
				listOf(
					node("com.example.app.MainActivity", retained = 60, count = 3, shallow = 8),
					node("byte[]", retained = 40, count = 1, shallow = 40),
				),
		)

	@Test
	fun classifiesFrameworkClasses() {
		assertTrue(isFrameworkClass("java.lang.String"))
		assertTrue(isFrameworkClass("android.graphics.Bitmap"))
		assertTrue(isFrameworkClass("class kotlin.Unit"))
		assertFalse(isFrameworkClass("com.example.app.MainActivity"))
		assertFalse(isFrameworkClass("byte[]"))
		assertFalse(isFrameworkClass(HeapDominators.ROOT_LABEL))
	}

	@Test
	fun nodeAtPathResolvesAndRejectsStaleKeys() {
		assertEquals("(heap)", tree.nodeAtPath(null)?.label)
		assertEquals("com.example.app.MainActivity", tree.nodeAtPath("0")?.label)
		assertEquals("byte[]", tree.nodeAtPath("1")?.label)
		assertNull(tree.nodeAtPath("9"))
		assertNull(tree.nodeAtPath("0/9"))
	}

	@Test
	fun pathLabelsIncludeRoot() {
		assertEquals(listOf("(heap)"), tree.pathLabels(null))
		assertEquals(listOf("(heap)", "byte[]"), tree.pathLabels("1"))
		assertEquals(emptyList<String>(), tree.pathLabels("9"))
	}

	@Test
	fun maxShallowBytesScansSubtree() {
		assertEquals(40L, tree.maxShallowBytes())
	}

	@Test
	fun collapseFrameworkLiftsAppChildrenAndMergesCollisions() {
		// root -> java.util.HashMap (framework) -> {com.example.A x2 via two framework parents}
		val collapsed =
			node(
				"(heap)",
				retained = 100,
				count = 4,
				children =
					listOf(
						node(
							"java.util.HashMap",
							retained = 50,
							count = 2,
							shallow = 30,
							children = listOf(node("com.example.A", retained = 20, count = 1, shallow = 5)),
						),
						node(
							"android.os.Bundle",
							retained = 30,
							count = 1,
							shallow = 10,
							children = listOf(node("com.example.A", retained = 10, count = 1, shallow = 5)),
						),
					),
			).collapseFrameworkClasses()

		// Both framework nodes are gone; their "com.example.A" children are lifted to the root and
		// merged (one node, retained/shallow/count summed).
		assertEquals(listOf("com.example.A"), collapsed.children.map { it.label })
		val a = collapsed.children.single()
		assertEquals(30L, a.retainedBytes)
		assertEquals(10L, a.shallowBytes)
		assertEquals(2L, a.retainedCount)
		// The root itself is preserved.
		assertEquals(100L, collapsed.retainedBytes)
	}
}
