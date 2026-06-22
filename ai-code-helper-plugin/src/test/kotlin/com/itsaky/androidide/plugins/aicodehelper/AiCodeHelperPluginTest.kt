package com.itsaky.androidide.plugins.aicodehelper

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.PluginLogger
import com.itsaky.androidide.plugins.ServiceRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.junit.Assert.*

class AiCodeHelperPluginTest {

    @Test
    fun testPluginInitialization() {
        val mockLogger = mockk<PluginLogger>(relaxed = true)
        val mockServiceRegistry = mockk<ServiceRegistry>(relaxed = true)
        val mockContext = mockk<PluginContext> {
            every { logger } returns mockLogger
            every { services } returns mockServiceRegistry
        }

        val plugin = AiCodeHelperPlugin()
        val result = plugin.initialize(mockContext)

        assertTrue(result)
        verify { mockLogger.info("AiCodeHelperPlugin: Plugin initialized successfully") }
    }

    @Test
    fun testPluginActivation() {
        val mockLogger = mockk<PluginLogger>(relaxed = true)
        val mockServiceRegistry = mockk<ServiceRegistry>(relaxed = true)
        val mockContext = mockk<PluginContext> {
            every { logger } returns mockLogger
            every { services } returns mockServiceRegistry
        }

        val plugin = AiCodeHelperPlugin()
        plugin.initialize(mockContext)
        val result = plugin.activate()

        assertTrue(result)
        verify { mockLogger.info("AiCodeHelperPlugin: Activating plugin") }
    }
}
