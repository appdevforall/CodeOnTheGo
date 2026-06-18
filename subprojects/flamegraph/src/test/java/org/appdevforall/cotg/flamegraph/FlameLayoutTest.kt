package org.appdevforall.cotg.flamegraph

import androidx.compose.ui.graphics.Color
import org.appdevforall.cotg.flamegraph.internal.isInSubtree
import org.appdevforall.cotg.flamegraph.internal.layoutFlame
import org.appdevforall.cotg.flamegraph.model.FlameNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlameLayoutTest {

    private val noColor: (String, Int) -> Color = { _, _ -> Color.Unspecified }

    @Test
    fun singleNodeFillsWidth() {
        val layout = layoutFlame(FlameNode<Nothing>("0", "root", 1.0), null, noColor)
        assertEquals(1, layout.frames.size)
        val frame = layout.frames.single()
        assertEquals(0f, frame.xFraction, 0.0001f)
        assertEquals(1f, frame.widthFraction, 0.0001f)
        assertEquals(0, layout.maxDepth)
    }

    @Test
    fun emptyTreeProducesNoFrames() {
        val layout = layoutFlame(FlameNode<Nothing>("0", "root", 0.0), null, noColor)
        assertTrue(layout.isEmpty)
    }

    @Test
    fun childrenSubdivideParentWidth() {
        val layout = layoutFlame(consistentRoot(), null, noColor)
        val a = layout.framesByDepth[1][0]
        val b = layout.framesByDepth[1][1]
        assertEquals(0f, a.xFraction, 0.0001f)
        assertEquals(0.6f, a.widthFraction, 0.0001f)
        assertEquals(0.6f, b.xFraction, 0.0001f)
        assertEquals(0.4f, b.widthFraction, 0.0001f)
    }

    @Test
    fun inconsistentValueDoesNotOverflow() {
        val root = FlameNode<Nothing>(
            "0", "root", 1.0,
            children = listOf(FlameNode("0/0", "a", 6.0), FlameNode("0/1", "b", 4.0)),
        )
        val layout = layoutFlame(root, null, noColor)
        val rightEdge = layout.framesByDepth[1].maxOf { it.xFraction + it.widthFraction }
        assertTrue("right edge $rightEdge must stay within bounds", rightEdge <= 1.0001f)
    }

    @Test
    fun tinyChildrenAreCulled() {
        val root = FlameNode<Nothing>(
            "0", "root", 10000.0,
            children = listOf(FlameNode("0/0", "big", 9999.0), FlameNode("0/1", "tiny", 1.0)),
        )
        val labels = layoutFlame(root, null, noColor).frames.map { it.label }
        assertTrue(labels.contains("big"))
        assertFalse(labels.contains("tiny"))
    }

    @Test
    fun deepTreeLaysOutWithoutStackOverflow() {
        var node = FlameNode<Nothing>("leaf", "f", 1.0)
        repeat(10_000) { node = FlameNode("n$it", "f", 1.0, children = listOf(node)) }
        val layout = layoutFlame(node, null, noColor)
        assertEquals(10_000, layout.maxDepth)
    }

    @Test
    fun frameAtHitTestsByFraction() {
        val layout = layoutFlame(consistentRoot(), null, noColor)
        assertEquals("a", layout.frameAt(1, 0.1f)?.label)
        assertEquals("a", layout.frameAt(1, 0.59f)?.label)
        assertEquals("b", layout.frameAt(1, 0.61f)?.label)
        assertEquals("root", layout.frameAt(0, 0.5f)?.label)
    }

    @Test
    fun focusRebasesSubtreeToFullWidth() {
        val root = FlameNode<Nothing>(
            "0", "root", 10.0,
            children = listOf(
                FlameNode("0/0", "a", 6.0, children = listOf(FlameNode("0/0/0", "a1", 6.0))),
                FlameNode("0/1", "b", 4.0),
            ),
        )
        val layout = layoutFlame(root, focusedKey = "0", colorOf = noColor)
        val focusRoot = layout.framesByDepth[0].single()
        assertEquals("a", focusRoot.label)
        assertEquals(1f, focusRoot.widthFraction, 0.0001f)
        assertEquals(1f, layout.framesByDepth[1].single().widthFraction, 0.0001f)
    }

    @Test
    fun subtreeMembershipForHighlighting() {
        assertTrue(isInSubtree("0/1", null))      // no selection highlights everything
        assertTrue(isInSubtree("0/1", ""))        // empty selection highlights everything
        assertTrue(isInSubtree("0/1", "0/1"))     // the selected frame itself
        assertTrue(isInSubtree("0/1/2", "0/1"))   // a descendant
        assertFalse(isInSubtree("0/2", "0/1"))    // a sibling
        assertFalse(isInSubtree("0/10", "0/1"))   // shared prefix but not a descendant
        assertFalse(isInSubtree("0", "0/1"))      // an ancestor is not in the subtree
    }

    private fun consistentRoot(): FlameNode<Nothing> =
        FlameNode(
            "0", "root", 10.0,
            children = listOf(FlameNode("0/0", "a", 6.0), FlameNode("0/1", "b", 4.0)),
        )
}
