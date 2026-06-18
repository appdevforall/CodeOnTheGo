package org.appdevforall.cotg.flamegraph

import org.appdevforall.cotg.flamegraph.model.buildFlameTree
import org.appdevforall.cotg.flamegraph.model.parseFoldedStacks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FoldedStacksTest {

    @Test
    fun buildsInclusiveTree() {
        val tree = buildFlameTree(
            listOf(
                listOf("a", "b", "c") to 5.0,
                listOf("a", "b") to 3.0,
            ),
        )
        assertEquals(8.0, tree.value, 0.0)
        val a = tree.children.single()
        assertEquals("a", a.label)
        assertEquals(8.0, a.value, 0.0)
        val b = a.children.single()
        assertEquals(8.0, b.value, 0.0)
        val c = b.children.single()
        assertEquals("c", c.label)
        assertEquals(5.0, c.value, 0.0)
    }

    @Test
    fun mergesSiblingsAndAssignsStableIds() {
        val tree = buildFlameTree(
            listOf(
                listOf("a", "b") to 1.0,
                listOf("a", "c") to 2.0,
            ),
        )
        val a = tree.children.single()
        assertEquals(3.0, a.value, 0.0)
        assertEquals(listOf("b", "c"), a.children.map { it.label })
        assertEquals(listOf("0/0/0", "0/0/1"), a.children.map { it.id })
    }

    @Test
    fun parsesFoldedText() {
        val tree = parseFoldedStacks("a;b;c 5\n\na;b 3\n")
        assertEquals(8.0, tree.value, 0.0)
        assertEquals("c", tree.children.single().children.single().children.single().label)
    }

    @Test
    fun framesMayContainSpaces() {
        val tree = parseFoldedStacks("java.Foo bar();baz 7")
        assertEquals(7.0, tree.value, 0.0)
        val first = tree.children.single()
        assertEquals("java.Foo bar()", first.label)
        assertEquals("baz", first.children.single().label)
    }

    @Test
    fun blankLinesAreIgnored() {
        val tree = parseFoldedStacks("\n   \na 2\n")
        assertEquals(2.0, tree.value, 0.0)
    }

    @Test
    fun malformedLineThrows() {
        assertThrows(IllegalArgumentException::class.java) { parseFoldedStacks("no-value-here") }
    }
}
