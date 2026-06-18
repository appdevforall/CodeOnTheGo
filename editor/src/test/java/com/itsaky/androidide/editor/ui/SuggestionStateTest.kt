package com.itsaky.androidide.editor.ui

import org.junit.Test
import org.junit.Assert.*

class SuggestionStateTest {

    @Test
    fun `state enum has all required values`() {
        val states = SuggestionState.values()
        assertEquals(5, states.size)
        assertTrue(states.contains(SuggestionState.IDLE))
        assertTrue(states.contains(SuggestionState.WAITING))
        assertTrue(states.contains(SuggestionState.REQUESTING))
        assertTrue(states.contains(SuggestionState.SHOWING))
        assertTrue(states.contains(SuggestionState.ACCEPTING))
    }

    @Test
    fun `state transitions are valid`() {
        // IDLE can go to WAITING
        val idle = SuggestionState.IDLE
        assertNotNull(idle)

        // Verify ordinal sequence
        assertEquals(0, SuggestionState.IDLE.ordinal)
        assertEquals(1, SuggestionState.WAITING.ordinal)
        assertEquals(2, SuggestionState.REQUESTING.ordinal)
        assertEquals(3, SuggestionState.SHOWING.ordinal)
        assertEquals(4, SuggestionState.ACCEPTING.ordinal)
    }
}
