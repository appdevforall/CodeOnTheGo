package com.itsaky.androidide.plugins.manager.security

import com.itsaky.androidide.plugins.PluginApiVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginApiVersionCheckerTest {

    @Test
    fun `same version is compatible`() {
        assertTrue(isCompatible(required = "1.0.0", current = "1.0.0"))
    }

    @Test
    fun `IDE with newer minor accepts plugin built against older minor`() {
        assertTrue(isCompatible(required = "1.0.0", current = "1.5.0"))
    }

    @Test
    fun `IDE with newer patch accepts plugin built against older patch`() {
        assertTrue(isCompatible(required = "1.0.0", current = "1.0.5"))
    }

    @Test
    fun `plugin requiring newer minor is incompatible`() {
        assertFalse(isCompatible(required = "1.5.0", current = "1.0.0"))
    }

    @Test
    fun `plugin requiring newer patch within same minor is incompatible`() {
        assertFalse(isCompatible(required = "1.0.5", current = "1.0.0"))
    }

    @Test
    fun `plugin built for older major is incompatible`() {
        assertFalse(isCompatible(required = "1.0.0", current = "2.0.0"))
    }

    @Test
    fun `plugin built for newer major is incompatible`() {
        assertFalse(isCompatible(required = "2.0.0", current = "1.0.0"))
    }

    @Test
    fun `requireCompatible does not throw on compatible versions`() {
        PluginApiVersionChecker.requireCompatible(
            "p",
            requiredRaw = "1.0.0",
            current = PluginApiVersion.parseOrThrow("1.5.0"),
        )
    }

    @Test
    fun `requireCompatible throws MAJOR_MISMATCH for cross-major rejection`() {
        val ex = assertThrows(PluginApiIncompatibleException::class.java) {
            PluginApiVersionChecker.requireCompatible(
                "plugin.x",
                requiredRaw = "2.0.0",
                current = PluginApiVersion.parseOrThrow("1.0.0"),
            )
        }
        assertEquals("plugin.x", ex.pluginId)
        assertEquals("2.0.0", ex.requiredVersion)
        assertEquals("1.0.0", ex.availableVersion)
        assertEquals(PluginApiIncompatibleException.Reason.MAJOR_MISMATCH, ex.reason)
    }

    @Test
    fun `requireCompatible throws REQUIRES_NEWER when plugin asks newer minor`() {
        val ex = assertThrows(PluginApiIncompatibleException::class.java) {
            PluginApiVersionChecker.requireCompatible(
                "plugin.y",
                requiredRaw = "1.5.0",
                current = PluginApiVersion.parseOrThrow("1.0.0"),
            )
        }
        assertEquals(PluginApiIncompatibleException.Reason.REQUIRES_NEWER, ex.reason)
    }

    @Test
    fun `requireCompatible throws MALFORMED_VERSION for unparseable input`() {
        val ex = assertThrows(PluginApiIncompatibleException::class.java) {
            PluginApiVersionChecker.requireCompatible(
                "plugin.z",
                requiredRaw = "not-a-version",
                current = PluginApiVersion.parseOrThrow("1.0.0"),
            )
        }
        assertEquals(PluginApiIncompatibleException.Reason.MALFORMED_VERSION, ex.reason)
        assertEquals("not-a-version", ex.requiredVersion)
    }

    @Test
    fun `requireCompatible throws REQUIRES_NEWER for newer patch within same minor`() {
        val ex = assertThrows(PluginApiIncompatibleException::class.java) {
            PluginApiVersionChecker.requireCompatible(
                "p",
                requiredRaw = "1.0.5",
                current = PluginApiVersion.parseOrThrow("1.0.0"),
            )
        }
        assertEquals(PluginApiIncompatibleException.Reason.REQUIRES_NEWER, ex.reason)
    }

    private fun isCompatible(required: String, current: String): Boolean =
        PluginApiVersionChecker.isCompatible(
            required = PluginApiVersion.parseOrThrow(required),
            current = PluginApiVersion.parseOrThrow(current),
        )
}
