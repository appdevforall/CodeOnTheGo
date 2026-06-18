package com.itsaky.androidide.editor.ui

import com.itsaky.androidide.models.Position
import org.junit.Test
import org.junit.Assert.*

class SuggestionDataTest {

    @Test
    fun `creates suggestion with all fields`() {
        val position = Position(10, 5, 0)
        val timestamp = System.currentTimeMillis()

        val suggestion = SuggestionData(
            text = "fun test() {\n    println(\"hello\")\n}",
            startPosition = position,
            cursorLine = 10,
            cursorColumn = 5,
            requestTimestamp = timestamp
        )

        assertEquals("fun test() {\n    println(\"hello\")\n}", suggestion.text)
        assertEquals(position, suggestion.startPosition)
        assertEquals(10, suggestion.cursorLine)
        assertEquals(5, suggestion.cursorColumn)
        assertEquals(timestamp, suggestion.requestTimestamp)
    }

    @Test
    fun `handles multi-line text correctly`() {
        val suggestion = SuggestionData(
            text = "line1\nline2\nline3",
            startPosition = Position(0, 0, 0),
            cursorLine = 0,
            cursorColumn = 0,
            requestTimestamp = 0L
        )

        val lines = suggestion.text.split("\n")
        assertEquals(3, lines.size)
        assertEquals("line1", lines[0])
        assertEquals("line2", lines[1])
        assertEquals("line3", lines[2])
    }

    @Test
    fun `equals and hashCode work correctly`() {
        val pos = Position(1, 2, 0)
        val s1 = SuggestionData("text", pos, 1, 2, 100L)
        val s2 = SuggestionData("text", pos, 1, 2, 100L)
        val s3 = SuggestionData("other", pos, 1, 2, 100L)

        assertEquals(s1, s2)
        assertEquals(s1.hashCode(), s2.hashCode())
        assertNotEquals(s1, s3)
    }
}
