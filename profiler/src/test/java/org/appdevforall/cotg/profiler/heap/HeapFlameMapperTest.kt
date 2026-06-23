package org.appdevforall.cotg.profiler.heap

import org.junit.Assert.assertEquals
import org.junit.Test

class HeapFlameMapperTest {

    private val tree = HeapObjectNode(
        label = "(heap)", shallowBytes = 0, retainedBytes = 300, retainedCount = 6,
        children = listOf(
            HeapObjectNode("A", shallowBytes = 10, retainedBytes = 200, retainedCount = 4, children = emptyList()),
            HeapObjectNode("B", shallowBytes = 20, retainedBytes = 100, retainedCount = 2, children = emptyList()),
        ),
    )

    @Test
    fun widthFollowsRetainedBytesMetric() {
        val flame = tree.toFlameNode(HeapMetric.RetainedBytes)
        assertEquals(300.0, flame.value, 0.0)
        assertEquals(200.0, flame.children[0].value, 0.0)
        assertEquals(100.0, flame.children[1].value, 0.0)
    }

    @Test
    fun widthFollowsInstanceCountMetric() {
        val flame = tree.toFlameNode(HeapMetric.InstanceCount)
        assertEquals(6.0, flame.value, 0.0)
        assertEquals(4.0, flame.children[0].value, 0.0)
        assertEquals(2.0, flame.children[1].value, 0.0)
    }

    @Test
    fun idsAndLabelsFollowPathScheme() {
        val flame = tree.toFlameNode(HeapMetric.RetainedBytes)
        assertEquals("0", flame.id)
        assertEquals("(heap)", flame.label)
        assertEquals("0/0", flame.children[0].id)
        assertEquals("A", flame.children[0].label)
        assertEquals("0/1", flame.children[1].id)
    }
}
