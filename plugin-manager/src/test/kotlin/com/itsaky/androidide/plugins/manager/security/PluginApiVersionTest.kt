package com.itsaky.androidide.plugins.manager.security

import com.itsaky.androidide.plugins.PluginApiVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginApiVersionTest {

    @Test
    fun `parse accepts full semver`() {
        val v = PluginApiVersion.parseOrThrow("2.3.4")
        assertEquals(2, v.major)
        assertEquals(3, v.minor)
        assertEquals(4, v.patch)
        assertEquals("2.3.4", v.raw)
    }

    @Test
    fun `parse accepts major-only shorthand and treats missing parts as zero`() {
        val v = PluginApiVersion.parseOrThrow("1")
        assertEquals(1, v.major)
        assertEquals(0, v.minor)
        assertEquals(0, v.patch)
    }

    @Test
    fun `parse accepts major-minor shorthand`() {
        val v = PluginApiVersion.parseOrThrow("1.2")
        assertEquals(1, v.major)
        assertEquals(2, v.minor)
        assertEquals(0, v.patch)
    }

    @Test
    fun `parse trims surrounding whitespace`() {
        assertNotNull(PluginApiVersion.parse("  1.0.0  "))
    }

    @Test
    fun `parse rejects malformed input by returning null`() {
        assertNull(PluginApiVersion.parse("v1.0.0"))
        assertNull(PluginApiVersion.parse("1.0.0-snapshot"))
        assertNull(PluginApiVersion.parse("1.0.0.0"))
        assertNull(PluginApiVersion.parse(""))
        assertNull(PluginApiVersion.parse("abc"))
        assertNull(PluginApiVersion.parse("1.x.0"))
    }

    @Test
    fun `parseOrThrow throws on malformed input instead of returning null`() {
        assertThrows(IllegalArgumentException::class.java) {
            PluginApiVersion.parseOrThrow("not-a-version")
        }
    }

    @Test
    fun `compareTo orders by major then minor then patch`() {
        val a = PluginApiVersion.parseOrThrow("1.2.3")
        val b = PluginApiVersion.parseOrThrow("1.2.4")
        val c = PluginApiVersion.parseOrThrow("1.3.0")
        val d = PluginApiVersion.parseOrThrow("2.0.0")

        assertTrue(a < b)
        assertTrue(b < c)
        assertTrue(c < d)
        assertEquals(0, a.compareTo(PluginApiVersion.parseOrThrow("1.2.3")))
    }

    @Test
    fun `toString returns raw form`() {
        assertEquals("1.0.0", PluginApiVersion.parseOrThrow("1.0.0").toString())
        assertEquals("1.2.3", PluginApiVersion.parseOrThrow("  1.2.3  ").toString())
    }

    @Test
    fun `CURRENT is parseable and well-formed`() {
        val current = PluginApiVersion.CURRENT
        assertTrue(current.major >= 1)
        assertEquals(current, PluginApiVersion.parseOrThrow(current.raw))
    }
}
