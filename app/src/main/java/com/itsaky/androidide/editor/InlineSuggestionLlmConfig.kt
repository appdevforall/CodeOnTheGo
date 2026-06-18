/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.editor

import android.content.Context
import com.itsaky.androidide.agent.repository.LlmInferenceEngineProvider
import com.itsaky.androidide.agent.repository.ModelPurpose
import com.itsaky.androidide.agent.repository.ModelPurpose.Companion.getPreferenceKey
import com.itsaky.androidide.agent.repository.ModelPurpose.Companion.getSha256PreferenceKey
import com.itsaky.androidide.app.BaseApplication
import com.itsaky.androidide.editor.ui.SuggestionProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/**
 * Configures LLM integration for inline code suggestions.
 *
 * Call [configure] once at app startup to wire up the LLM engine.
 */
object InlineSuggestionLlmConfig {

    private val log = LoggerFactory.getLogger(InlineSuggestionLlmConfig::class.java)
    private var configured = false

    /**
     * Configure LLM integration for inline suggestions.
     * Should be called once during app initialization.
     */
    fun configure() {
        if (configured) {
            log.warn("LLM already configured, skipping")
            return
        }

        log.info("Configuring LLM for inline suggestions")

        val engine = LlmInferenceEngineProvider.instance

        // Inject LLM inference function
        SuggestionProvider.llmInference = { prompt, stopStrings, clearCache ->
            engine.runInference(
                prompt = prompt,
                stopStrings = stopStrings,
                clearCache = clearCache
            )
        }

        // Inject model loader function for lazy loading
        SuggestionProvider.llmModelLoader = llmModelLoader@{ context ->
            try {
                val prefs = BaseApplication.baseInstance.prefManager
                val modelPath = prefs.getString(ModelPurpose.CODE_COMPLETION.getPreferenceKey(), null)
                val modelHash = prefs.getString(ModelPurpose.CODE_COMPLETION.getSha256PreferenceKey(), null)

                if (modelPath.isNullOrBlank()) {
                    log.info("No CODE_COMPLETION model configured. Please select a model in AI Settings.")
                    return@llmModelLoader false
                }

                log.info("Lazy loading CODE_COMPLETION model: $modelPath")

                val loaded = withContext(Dispatchers.IO) {
                    engine.initModelFromFile(context, modelPath, modelHash)
                }

                if (loaded) {
                    log.info("Successfully loaded CODE_COMPLETION model: ${engine.loadedModelName}")
                } else {
                    log.error("Failed to load CODE_COMPLETION model from: $modelPath")
                }

                loaded
            } catch (e: Exception) {
                log.error("Error during lazy model loading", e)
                false
            }
        }

        // Inject model check function with validation
        SuggestionProvider.llmModelCheck = llmModelCheck@{
            val isLoaded = engine.isModelLoaded
            val modelName = engine.loadedModelName ?: "unknown"

            if (isLoaded) {
                // Check if it's an embedding model (not suitable for text generation)
                val isEmbeddingModel = modelName.contains("minilm", ignoreCase = true) ||
                                      modelName.contains("embedding", ignoreCase = true) ||
                                      modelName.contains("bge-", ignoreCase = true) ||
                                      modelName.contains("e5-", ignoreCase = true)

                if (isEmbeddingModel) {
                    log.warn("Embedding model detected: $modelName - not suitable for code completion")
                    log.warn("Please load a generative model (CodeLlama, DeepSeek Coder, etc.) for code completion")
                    return@llmModelCheck false
                }

                log.debug("Model check passed: $modelName")
            } else {
                log.debug("No model loaded for inline suggestions")
            }

            isLoaded
        }

        configured = true
        log.info("LLM configuration complete")
    }

    /**
     * Check if LLM is configured.
     */
    fun isConfigured(): Boolean = configured
}
