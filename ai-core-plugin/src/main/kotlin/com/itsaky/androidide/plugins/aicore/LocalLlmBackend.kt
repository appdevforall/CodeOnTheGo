package com.itsaky.androidide.plugins.aicore

import com.itsaky.androidide.plugins.services.LlmInferenceService.*
import java.util.concurrent.CompletableFuture

/**
 * Local LLM backend using llama-impl for on-device inference.
 * Wraps llama-impl APIs and implements LlmBackend interface.
 */
class LocalLlmBackend : LlmBackend {

    @Volatile private var isInitialized = false

    override fun getId(): String = "local"

    override fun getName(): String = "Local LLM"

    override fun isAvailable(): Boolean {
        // In real implementation, check if model is loaded
        return isInitialized
    }

    override fun generate(prompt: String, config: LlmConfig): CompletableFuture<LlmResponse> {
        if (!isAvailable()) {
            return CompletableFuture.completedFuture(
                LlmResponse.failure("Local LLM backend is not available. Model not loaded.")
            )
        }

        // Stub: real implementation will call llama-impl
        return CompletableFuture.supplyAsync {
            LlmResponse.success("Stub response from local LLM", 10, 100)
        }
    }

    override fun generateStreaming(prompt: String, config: LlmConfig, callback: StreamCallback) {
        if (!isAvailable()) {
            callback.onError("Local LLM backend is not available. Model not loaded.")
            return
        }

        // Stub: real implementation will call llama-impl streaming API
        callback.onToken("Stub")
        callback.onToken(" response")
        callback.onComplete(LlmResponse.success("Stub response", 2, 50))
    }

    override fun generateWithHistory(
        history: List<ChatMessage>,
        prompt: String,
        config: LlmConfig
    ): CompletableFuture<LlmResponse> {
        if (!isAvailable()) {
            return CompletableFuture.completedFuture(
                LlmResponse.failure("Local LLM backend is not available. Model not loaded.")
            )
        }

        // Stub: real implementation will format history and call llama-impl
        return CompletableFuture.supplyAsync {
            LlmResponse.success("Stub response with history", 10, 100)
        }
    }
}
