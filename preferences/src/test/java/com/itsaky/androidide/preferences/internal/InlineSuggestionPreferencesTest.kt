package com.itsaky.androidide.preferences.internal

import org.junit.Test
import org.junit.Assert.*

class InlineSuggestionPreferencesTest {

    @Test
    fun `preference keys are defined`() {
        assertEquals("inline_suggestion_enabled", InlineSuggestionPreferences.ENABLED)
        assertEquals("inline_suggestion_auto_trigger", InlineSuggestionPreferences.AUTO_TRIGGER)
        assertEquals("inline_suggestion_char_threshold", InlineSuggestionPreferences.CHAR_THRESHOLD)
        assertEquals("inline_suggestion_debounce_ms", InlineSuggestionPreferences.DEBOUNCE_MS)
        assertEquals("inline_suggestion_max_lines", InlineSuggestionPreferences.MAX_LINES)
        assertEquals("inline_suggestion_manual_shortcut", InlineSuggestionPreferences.MANUAL_SHORTCUT)
        assertEquals("inline_suggestion_toolbar_button", InlineSuggestionPreferences.SHOW_TOOLBAR_BUTTON)
    }

    @Test
    fun `default values are correct`() {
        // Verify defaults match global constraints
        assertEquals(true, InlineSuggestionPreferences.DEFAULT_ENABLED)
        assertEquals(true, InlineSuggestionPreferences.DEFAULT_AUTO_TRIGGER)
        assertEquals(3, InlineSuggestionPreferences.DEFAULT_CHAR_THRESHOLD)
        assertEquals(300, InlineSuggestionPreferences.DEFAULT_DEBOUNCE_MS)
        assertEquals(5, InlineSuggestionPreferences.DEFAULT_MAX_LINES)
        assertEquals("Ctrl+Space", InlineSuggestionPreferences.DEFAULT_MANUAL_SHORTCUT)
        assertEquals(true, InlineSuggestionPreferences.DEFAULT_SHOW_TOOLBAR_BUTTON)
    }
}
