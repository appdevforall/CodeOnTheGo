package com.itsaky.androidide.plugins.aicore

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.PluginLogger
import com.itsaky.androidide.plugins.ServiceRegistry
import com.itsaky.androidide.plugins.services.LlmInferenceService
import com.itsaky.androidide.plugins.manager.context.ServiceRegistryImpl
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.junit.Assert.*

class AiCorePluginTest {

    @Test
    fun testPluginInitialization() {
        val mockLogger = mockk<PluginLogger>(relaxed = true)
        val mockServiceRegistry = mockk<ServiceRegistry>(relaxed = true)
        val mockContext = mockk<PluginContext> {
            every { logger } returns mockLogger
            every { services } returns mockServiceRegistry
        }

        val plugin = AiCorePlugin()
        val result = plugin.initialize(mockContext)

        assertTrue(result)
        verify { mockLogger.info("AiCorePlugin: Plugin initialized successfully") }
    }

    @Test
    fun testPluginActivation() {
        val mockLogger = mockk<PluginLogger>(relaxed = true)
        val mockServiceRegistry = mockk<ServiceRegistry>(relaxed = true)
        val mockContext = mockk<PluginContext> {
            every { logger } returns mockLogger
            every { services } returns mockServiceRegistry
        }

        val plugin = AiCorePlugin()
        plugin.initialize(mockContext)
        val result = plugin.activate()

        assertTrue(result)
        verify { mockLogger.info("AiCorePlugin: Activating plugin") }
    }

    @Test
    fun testServiceRegistration() {
        val mockLogger = mockk<PluginLogger>(relaxed = true)
        val mockServiceRegistry = mockk<ServiceRegistry>(relaxed = true)
        val mockContext = mockk<PluginContext> {
            every { logger } returns mockLogger
            every { services } returns mockServiceRegistry
        }

        val plugin = AiCorePlugin()
        plugin.initialize(mockContext)
        plugin.activate()

        verify {
            mockServiceRegistry.register(
                LlmInferenceService::class.java,
                any<LlmInferenceService>()
            )
        }
    }

    @Test
    fun testLocalBackendRegistration() {
        val mockLogger = mockk<PluginLogger>(relaxed = true)
        val serviceRegistryImpl = ServiceRegistryImpl()
        val mockContext = mockk<PluginContext> {
            every { logger } returns mockLogger
            every { services } returns serviceRegistryImpl
        }

        val plugin = AiCorePlugin()
        plugin.initialize(mockContext)
        plugin.activate()

        val service = serviceRegistryImpl.get(LlmInferenceService::class.java)
        assertNotNull(service)

        val backend = service!!.getBackend("local")
        assertNotNull(backend)
        assertEquals("local", backend!!.getId())
    }
}
