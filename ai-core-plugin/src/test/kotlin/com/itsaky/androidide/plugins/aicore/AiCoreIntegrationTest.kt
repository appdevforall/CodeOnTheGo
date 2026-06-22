package com.itsaky.androidide.plugins.aicore

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.PluginLogger
import com.itsaky.androidide.plugins.services.LlmInferenceService
import com.itsaky.androidide.plugins.services.LlmInferenceService.*
import com.itsaky.androidide.plugins.manager.context.ServiceRegistryImpl
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import org.junit.Assert.*
import org.junit.Before
import org.junit.After

/**
 * Integration test demonstrating complete AI Core Plugin workflow.
 */
class AiCoreIntegrationTest {

    private lateinit var plugin: AiCorePlugin
    private lateinit var context: PluginContext

    @Before
    fun setup() {
        val mockLogger = mockk<PluginLogger>(relaxed = true)
        val serviceRegistry = ServiceRegistryImpl()

        context = mockk {
            every { logger } returns mockLogger
            every { services } returns serviceRegistry
        }

        plugin = AiCorePlugin()
    }

    @After
    fun teardown() {
        plugin.dispose()
    }

    @Test
    fun testCompletePluginWorkflow() {
        // Step 1: Initialize plugin
        val initSuccess = plugin.initialize(context)
        assertTrue("Plugin initialization should succeed", initSuccess)

        // Step 2: Activate plugin (registers service and backend)
        val activateSuccess = plugin.activate()
        assertTrue("Plugin activation should succeed", activateSuccess)

        // Step 3: Retrieve LlmInferenceService from context
        val service = context.services.get(LlmInferenceService::class.java)
        assertNotNull("LlmInferenceService should be registered", service)

        // Step 4: Verify local backend is registered
        val backends = service!!.getAvailableBackends()
        assertEquals("Should have 1 backend", 1, backends.size)
        assertEquals("Backend ID should be 'local'", "local", backends[0].getId())

        // Step 5: Check backend availability
        val isAvailable = service.isBackendAvailable("local")
        assertFalse("Backend should not be available (model not loaded)", isAvailable)

        // Step 6: Attempt generation with unavailable backend
        val config = LlmConfig("local")
        config.temperature = 0.7f
        config.maxTokens = 100

        val future = service.generateCompletion("Write a hello world function", config)
        val response = future.get()

        assertFalse("Response should fail (backend unavailable)", response.success)
        assertNotNull("Error message should be present", response.error)
        assertTrue("Error should mention availability",
            response.error!!.contains("not available"))

        // Step 7: Deactivate plugin
        val deactivateSuccess = plugin.deactivate()
        assertTrue("Plugin deactivation should succeed", deactivateSuccess)

        // Step 8: Verify backend unregistered
        val backendAfterDeactivate = service.getBackend("local")
        assertNull("Backend should be unregistered after deactivation", backendAfterDeactivate)
    }
}
