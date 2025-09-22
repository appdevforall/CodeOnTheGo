package com.itsaky.androidide.agent.repository

import com.google.genai.Client
import com.google.genai.errors.ServerException // NEW: Import ServerException
import com.google.genai.types.Content
import com.google.genai.types.CountTokensConfig
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.GenerateContentResponse
import com.google.genai.types.GetModelConfig
import com.google.genai.types.Tool
import org.slf4j.LoggerFactory
import kotlin.jvm.optionals.getOrNull
import kotlin.system.measureTimeMillis

class GeminiClient(
    val apiKey: String,
    val modelName: String
) {
    val client: Client = Client.builder().apiKey(apiKey).build()

    private val tokenLimit: Int? = try {
        val modelInfo = client.models.get("models/$modelName", GetModelConfig.builder().build())
        val inputTokenLimit = modelInfo.inputTokenLimit().getOrNull()
        log.info("Initialized GeminiClient for model $modelName with a limit of $inputTokenLimit tokens.")
        inputTokenLimit
    } catch (e: Exception) {
        log.error("Could not retrieve model information for $modelName. Token limit check will be disabled. Error: ${e.message}")
        null
    }

    companion object {
        private val log = LoggerFactory.getLogger(GeminiClient::class.java)
    }

    private fun checkTokenLimit(history: List<Content>) {
        if (tokenLimit == null) {
            log.warn("Token limit not set for model $modelName, skipping check.")
            return
        }
        try {
            val response =
                client.models.countTokens(modelName, history, CountTokensConfig.builder().build())
            val tokenCount = response.totalTokens().getOrNull() ?: 0
            log.info("Prompt contains $tokenCount tokens. Limit for $modelName is $tokenLimit.")

            if (tokenCount >= tokenLimit) {
                throw IllegalArgumentException(
                    "Prompt is too long. It contains $tokenCount tokens, but the limit for " +
                            "$modelName is $tokenLimit tokens."
                )
            }
        } catch (e: Exception) {
            log.error("An unexpected error occurred during token counting: ${e.message}. Proceeding with API call anyway.")
        }
    }

    /**
     * Generates content with a retry mechanism for server errors.
     */
    fun generateContent(
        history: List<Content>,
        tools: List<Tool>
    ): GenerateContentResponse {
        checkTokenLimit(history)

        // NEW: Retry Logic
        var attempts = 0
        val maxAttempts = 3 // Try a total of 3 times
        history.map { log.info("History: $it") }

        while (attempts < maxAttempts) {
            try {
                var response: GenerateContentResponse? = null
                var config = GenerateContentConfig.builder().tools(tools).build()
                if (tools.isEmpty()) {
                    config = GenerateContentConfig.builder().build()
                }
                log.info("config: $config")
                val apiCallDuration = measureTimeMillis {
                    log.debug("Gemini API call (Attempt ${attempts + 1}) will be called...")
                    response = client.models.generateContent(
                        modelName,
                        history,
                        config
                    )
                }
                log.info("Gemini API call to '$modelName' took $apiCallDuration ms.")

                log.info("Response: {}", response?.toJson())
                val candidates = response?.candidates()?.getOrNull()
                if (candidates.isNullOrEmpty()) {
                    val finishReason = response?.promptFeedback()?.get()?.blockReason()?.getOrNull()
                    throw Exception("API Error: No candidates returned. Prompt feedback reason: ${finishReason ?: "Unknown"}")
                }

                log.info("Candidate: {}", candidates.toString())
                val candidate = candidates[0]
                log.info("Candidate: {}", candidate.toJson())
                val content = candidate.content().getOrNull()

                if (content == null) {
                    // NEW: Get the finish reason from the candidate itself.
                    val finishReason = candidate.finishReason().getOrNull()

                    // NEW: Log the reason before throwing the error.
                    log.error("Candidate did not contain content. Finish Reason: {}", finishReason)

                    // NEW: Include the reason in the exception for better debugging.
                    throw Exception("Candidate did not contain any content. Finish Reason: $finishReason")
                }

                log.debug("Gemini API call returned the body:\n{}", content.toString())
                return response

            } catch (e: ServerException) {
                attempts++
                log.warn("ServerException (500) on attempt $attempts. Retrying...", e)
                if (attempts >= maxAttempts) {
                    // If we've exhausted all retries, re-throw the final exception
                    throw e
                }
                // Wait before retrying (1 second, then 2 seconds)
                Thread.sleep(attempts * 1000L)
            }
        }
        // This line should not be reachable, but is a safeguard
        throw IllegalStateException("Retry logic failed unexpectedly.")
    }
}