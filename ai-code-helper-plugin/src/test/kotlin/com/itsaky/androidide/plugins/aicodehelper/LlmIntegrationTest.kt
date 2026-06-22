package com.itsaky.androidide.plugins.aicodehelper

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.PluginLogger
import com.itsaky.androidide.plugins.ServiceRegistry
import com.itsaky.androidide.plugins.services.LlmInferenceService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.junit.Assert.*
import org.junit.Before
import java.util.concurrent.CompletableFuture

class LlmIntegrationTest {

    private lateinit var plugin: AiCodeHelperPlugin
    private lateinit var mockLlmService: LlmInferenceService
    private lateinit var mockLogger: PluginLogger

    @Before
    fun setup() {
        mockLogger = mockk(relaxed = true)
        mockLlmService = mockk(relaxed = true)
        val mockServiceRegistry = mockk<ServiceRegistry>(relaxed = true)
        every { mockServiceRegistry.get(LlmInferenceService::class.java) } returns mockLlmService

        val mockContext = mockk<PluginContext>(relaxed = true)
        every { mockContext.logger } returns mockLogger
        every { mockContext.services } returns mockServiceRegistry

        plugin = AiCodeHelperPlugin()
        plugin.initialize(mockContext)
        plugin.activate()
    }

    @Test
    fun testLlmServiceAvailableOnActivation() {
        // Plugin should check for LLM service during activation
        verify { mockLogger.info(match { it.contains("activated") || it.contains("Activating") }) }
    }

    @Test
    fun testExplainCodeCallsLlmService() {
        val testCode = "val x = 42"
        val mockResponse = LlmInferenceService.LlmResponse.success("This declares a variable", 10, 100)
        every { mockLlmService.generateCompletion(any(), any()) } returns
            CompletableFuture.completedFuture(mockResponse)

        // Trigger explain code via reflection (since explainCode is private)
        val explainMethod = plugin.javaClass.getDeclaredMethod("explainCode", String::class.java)
        explainMethod.isAccessible = true
        explainMethod.invoke(plugin, testCode)

        verify(timeout = 1000) {
            mockLlmService.generateCompletion(
                match { it.contains("Explain") && it.contains(testCode) },
                any()
            )
        }
    }
}
