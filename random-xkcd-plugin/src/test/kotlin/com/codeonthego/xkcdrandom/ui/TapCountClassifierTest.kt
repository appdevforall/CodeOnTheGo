package com.codeonthego.xkcdrandom.ui

import com.codeonthego.xkcdrandom.ui.TapCountClassifier.Classification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the tap-count state machine. Pure JUnit — no Robolectric,
 * no Android dependencies. Synthetic timestamps so we control "now" exactly.
 */
class TapCountClassifierTest {

    @Test
    fun `single tap resolves to SINGLE`() {
        val c = TapCountClassifier(windowMillis = 300L)
        assertFalse(c.onTap(now = 1000L))
        assertEquals(Classification.SINGLE, c.resolve())
    }

    @Test
    fun `two fast taps resolve to DOUBLE`() {
        val c = TapCountClassifier(windowMillis = 300L)
        assertFalse(c.onTap(now = 1000L))
        assertFalse(c.onTap(now = 1100L))  // 100ms later, within window
        assertEquals(Classification.DOUBLE, c.resolve())
    }

    @Test
    fun `slow second tap starts a new burst (treated as two singles)`() {
        val c = TapCountClassifier(windowMillis = 300L)
        c.onTap(now = 1000L)
        assertEquals(Classification.SINGLE, c.resolve())  // first burst resolves
        c.onTap(now = 2000L)                              // 1s later — new burst
        assertEquals(Classification.SINGLE, c.resolve())
    }

    @Test
    fun `triple tap returns true on the third onTap (resolve early)`() {
        val c = TapCountClassifier(windowMillis = 300L)
        assertFalse(c.onTap(now = 1000L))
        assertFalse(c.onTap(now = 1100L))
        assertTrue(c.onTap(now = 1200L))  // third tap closes the burst
        assertEquals(Classification.TRIPLE, c.resolve())
    }

    @Test
    fun `four taps clamp to TRIPLE`() {
        val c = TapCountClassifier(windowMillis = 300L)
        c.onTap(now = 1000L)
        c.onTap(now = 1100L)
        c.onTap(now = 1200L)
        // Even though the third tap eagerly closes the burst, a fourth
        // tap before the host has resolved must not crash and must not
        // upgrade past TRIPLE.
        c.onTap(now = 1300L)
        assertEquals(Classification.TRIPLE, c.resolve())
    }

    @Test
    fun `tap after the window starts a new burst`() {
        val c = TapCountClassifier(windowMillis = 300L)
        c.onTap(now = 1000L)
        // Exactly at the boundary (now - last == window) → still within
        // the window, since the predicate is `> window`. One ms past
        // is the first that starts a new burst.
        c.onTap(now = 1301L)
        assertEquals(Classification.SINGLE, c.resolve())
    }

    @Test
    fun `tap exactly at the window boundary still extends the burst`() {
        val c = TapCountClassifier(windowMillis = 300L)
        c.onTap(now = 1000L)
        c.onTap(now = 1300L)  // now - last == window → still inside
        assertEquals(Classification.DOUBLE, c.resolve())
    }

    @Test
    fun `resolve with no taps returns null`() {
        val c = TapCountClassifier(windowMillis = 300L)
        assertNull(c.resolve())
    }

    @Test
    fun `resolve resets state for next burst`() {
        val c = TapCountClassifier(windowMillis = 300L)
        c.onTap(now = 1000L)
        c.onTap(now = 1100L)
        assertEquals(Classification.DOUBLE, c.resolve())
        // After resolve, next tap should be a fresh SINGLE.
        c.onTap(now = 5000L)
        assertEquals(Classification.SINGLE, c.resolve())
    }

    @Test
    fun `hasPendingBurst flips correctly`() {
        val c = TapCountClassifier(windowMillis = 300L)
        assertFalse(c.hasPendingBurst())
        c.onTap(now = 1000L)
        assertTrue(c.hasPendingBurst())
        c.resolve()
        assertFalse(c.hasPendingBurst())
    }
}
