package com.itsaky.androidide.agent.repository

import kotlinx.coroutines.Dispatchers

/**
 * Singleton provider for the LlmInferenceEngine.
 *
 * This ensures that a single instance of the engine is shared across the entire application,
 * preserving its state (like the currently loaded model).
 * The 'by lazy' delegate ensures the instance is created only when it's first accessed,
 * fulfilling the requirement for lazy, runtime initialization.
 */
object LlmInferenceEngineProvider {
    val instance: LlmInferenceEngine by lazy {
        LlmInferenceEngine(
            ioDispatcher = Dispatchers.IO
        )
    }
}