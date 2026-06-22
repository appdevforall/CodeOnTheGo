package com.itsaky.androidide.plugins.aicore

import com.itsaky.androidide.plugins.services.LlmInferenceService.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.Before

class LocalLlmBackendTest {

    private lateinit var backend: LocalLlmBackend

    @Before
    fun setup() {
        backend = LocalLlmBackend()
    }

    @Test
    fun testBackendId() {
        assertEquals("local", backend.getId())
    }

    @Test
    fun testBackendName() {
        assertEquals("Local LLM", backend.getName())
    }

    @Test
    fun testIsAvailableWhenNotInitialized() {
        // Backend requires model initialization, should be false initially
        assertFalse(backend.isAvailable())
    }

    @Test
    fun testGenerateReturnsErrorWhenNotAvailable() {
        val config = LlmConfig("local")
        val future = backend.generate("Test prompt", config)
        val response = future.get()

        assertFalse(response.success)
        assertNotNull(response.error)
        assertTrue(response.error!!.contains("not available"))
    }
}
