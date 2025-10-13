package com.itsaky.androidide.agent.repository

import com.google.genai.Client
import com.google.genai.errors.ClientException
import com.google.genai.errors.ServerException
import com.google.genai.types.Content
import com.google.genai.types.CountTokensConfig
import com.google.genai.types.FunctionCallingConfig
import com.google.genai.types.FunctionCallingConfigMode
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.GenerateContentResponse
import com.google.genai.types.GetModelConfig
import com.google.genai.types.Tool
import com.google.genai.types.ToolConfig
import org.slf4j.LoggerFactory
import kotlin.jvm.optionals.getOrNull
import kotlin.system.measureTimeMillis

class GeminiClient(
    val apiKey: String,
    val modelName: String
) {
    private val client: Client = Client.builder().apiKey(apiKey.trim()).build()

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

    /**
     * Public method to generate content. It now delegates the complex logic to helper functions.
     */
    fun generateContent(
        history: List<Content>,
        tools: List<Tool>,
        forceToolUse: Boolean = false
    ): GenerateContentResponse {
        checkTokenLimit(history)
        history.forEach { log.info("History: $it") }

        val config = buildGenerateContentConfig(tools, forceToolUse)
        log.info("config: $config")

        val response = executeWithRetry {
            client.models.generateContent(modelName, history, config)
        }

        return validateResponse(response)
    }

    /**
     * Builds the configuration object for the Gemini API call.
     */
    private fun buildGenerateContentConfig(
        tools: List<Tool>,
        forceToolUse: Boolean
    ): GenerateContentConfig {
        val configBuilder = GenerateContentConfig.builder().tools(tools)
        if (forceToolUse && tools.isNotEmpty()) {
            log.info("Tool use is being forced for this API call.")
            val toolConfig = ToolConfig.builder()
                .functionCallingConfig(
                    FunctionCallingConfig.builder()
                        .mode(FunctionCallingConfigMode.Known.ANY)
                        .build()
                ).build()
            configBuilder.toolConfig(toolConfig)
        }
        return configBuilder.build()
    }

    /**
     * A wrapper that executes a given API call with retry logic for server errors
     * and sanitized handling for client errors.
     */
    private fun <T> executeWithRetry(apiCall: () -> T): T {
        var attempts = 0
        val maxAttempts = 3
        while (attempts < maxAttempts) {
            try {
                var result: T? = null
                val apiCallDuration = measureTimeMillis {
                    log.debug("Gemini API call (Attempt ${attempts + 1}) will be called...")
                    result = apiCall()
                }
                log.info("Gemini API call to '$modelName' took $apiCallDuration ms.")
                return result!!
            } catch (e: ServerException) {
                attempts++
                log.warn("ServerException (500) on attempt $attempts. Retrying...", e)
                if (attempts >= maxAttempts) {
                    log.error("Failed to get a successful response after $maxAttempts attempts.")
                    throw e // Re-throw after final attempt fails
                }
                Thread.sleep(attempts * 1000L)
            } catch (e: ClientException) {
                // Any other client-side error should be sanitized and not retried.
                handleClientError(e)
            } catch (e: IllegalArgumentException) {
                // This specifically catches the bad API key error before it becomes a ClientException.
                handleClientError(e)
            }
        }
        throw IllegalStateException("Retry logic failed unexpectedly. This should not be reached.")
    }

    /**
     * Centralized function to handle all non-retryable client-side errors.
     * It logs the error and throws a new, sanitized exception to protect sensitive data.
     * The return type 'Nothing' indicates that this function never returns normally.
     */
    private fun handleClientError(e: Throwable): Nothing {
        val sanitizedMessage =
            "A client-side error occurred. This is often due to an invalid API key (e.g., with extra spaces or newlines) or a malformed request. Please check your configuration and credentials."
        log.error("$sanitizedMessage Original Error Type: ${e.javaClass.simpleName}", e)
        throw ClientException(400, "BAD_REQUEST", sanitizedMessage)
    }

    /**
     * Validates that the API response contains usable content.
     */
    private fun validateResponse(response: GenerateContentResponse): GenerateContentResponse {
        log.info("Response: {}", response.toJson())
        val candidates = response.candidates().getOrNull()
        if (candidates.isNullOrEmpty()) {
            val finishReason = response.promptFeedback()?.get()?.blockReason()?.getOrNull()
            throw Exception("API Error: No candidates returned. Prompt feedback reason: ${finishReason ?: "Unknown"}")
        }

        val candidate = candidates[0]
        log.info("Candidate: {}", candidate.toJson())
        val content = candidate.content().getOrNull()
        if (content == null) {
            val finishReason = candidate.finishReason().getOrNull()
            throw Exception("Candidate did not contain any content. Finish Reason: $finishReason")
        }

        log.debug("Gemini API call returned the body:\n{}", content.toString())
        return response
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
}