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

package com.itsaky.androidide.editor.ui

import android.content.Context
import android.util.LruCache
import com.itsaky.androidide.models.Position
import com.itsaky.androidide.preferences.internal.InlineSuggestionPreferences
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import kotlin.math.min

/**
 * Function type for requesting LLM inference.
 * Parameters: prompt, stopStrings, clearCache
 * Returns: completion text
 */
typealias LlmInferenceFunction = suspend (String, List<String>, Boolean) -> String

/**
 * Function type for checking if LLM model is loaded.
 */
typealias LlmModelCheckFunction = () -> Boolean

/**
 * Function type for lazy loading a model.
 * Takes a Context and returns true if model was successfully loaded.
 */
typealias LlmModelLoaderFunction = suspend (Context) -> Boolean

/**
 * Provides inline code suggestions from local LLM.
 *
 * Handles request management, caching, and cancellation.
 */
class SuggestionProvider(
    private val editor: IDEEditor,
    private val cache: SuggestionCache = DefaultSuggestionCache()
) {

    companion object {
        /**
         * LLM inference function to be injected by app module.
         * Set this once at app startup before editors are created.
         */
        @Volatile
        var llmInference: LlmInferenceFunction? = null

        /**
         * LLM model check function to be injected by app module.
         * Set this once at app startup before editors are created.
         */
        @Volatile
        var llmModelCheck: LlmModelCheckFunction? = null

        /**
         * LLM model loader function for lazy loading.
         * Set this once at app startup before editors are created.
         */
        @Volatile
        var llmModelLoader: LlmModelLoaderFunction? = null
    }

    private val log = LoggerFactory.getLogger(SuggestionProvider::class.java)

    // activeRequest will be assigned when LLM integration is added in Task 5
    // It will track the coroutine job for the current suggestion request
    // allowing cancellation of in-flight LLM queries
    private var activeRequest: Job? = null

    /**
     * Request a code suggestion for the given context.
     *
     * @return SuggestionData if available, null if no suggestion or error
     */
    suspend fun requestSuggestion(
        cursorPosition: Position,
        fileContent: String,
        language: String
    ): SuggestionData? {
        // Cancel any in-flight request
        cancelActiveRequest()

        // Check cache first
        val cacheKey = computeCacheKey(cursorPosition, fileContent, language)
        cache.get(cacheKey)?.let { cached ->
            val age = System.currentTimeMillis() - cached.requestTimestamp
            if (age < 30_000) {  // Global constraint: 30 second expiry
                log.debug("Cache hit for suggestion")
                return cached
            } else {
                cache.remove(cacheKey)
            }
        }

        return try {
            // Check if LLM is available and model is loaded
            if (llmInference == null || llmModelCheck == null) {
                log.debug("LLM not configured, cannot provide suggestions")
                return null
            }

            // Check if model is loaded, attempt lazy loading if not
            if (llmModelCheck?.invoke() != true) {
                log.debug("No model loaded, attempting lazy load...")

                // Try to lazy load the CODE_COMPLETION model
                val loaded = llmModelLoader?.invoke(editor.context)
                if (loaded == true) {
                    log.info("Model lazy loaded successfully")
                    // Verify it's not an embedding model after loading
                    if (llmModelCheck?.invoke() != true) {
                        log.warn("Loaded model failed validation (likely an embedding model)")
                        return null
                    }
                } else {
                    log.debug("No model loaded, cannot provide suggestions")
                    return null
                }
            }

            // Extract context around cursor. Keep this small: every request re-processes the whole
            // prompt (clearCache), so fewer context lines = faster prefill = lower latency.
            val (contextBefore, contextAfter) = extractContext(
                fileContent,
                cursorPosition,
                maxContextLines = 15
            )

            // Build prompt for code completion
            val prompt = buildCompletionPrompt(
                contextBefore = contextBefore,
                contextAfter = contextAfter,
                language = language,
                cursorLine = cursorPosition.line,
                cursorColumn = cursorPosition.column
            )

            log.debug("Requesting suggestion from LLM (context: ${contextBefore.length} chars before, ${contextAfter.length} chars after)")

            // Request completion with timeout (30 seconds)
            // IMPORTANT: Always clear KV cache to avoid state conflicts with chat/other operations
            val response = withTimeout(30_000) {
                llmInference?.invoke(
                    prompt,
                    listOf("\n\n", "```", "<|eot_id|>"),  // Stop at blank line or code fence
                    true  // Clear KV cache to ensure clean state for code completion
                ) ?: ""
            }

            // Clean and process response
            val cleanedSuggestion = cleanSuggestionText(response)

            if (cleanedSuggestion.isBlank()) {
                log.debug("LLM returned empty suggestion")
                return null
            }

            // Limit to max lines
            val limitedSuggestion = limitToMaxLines(
                cleanedSuggestion,
                InlineSuggestionPreferences.maxLines
            )

            // Create suggestion data
            val suggestion = SuggestionData(
                text = limitedSuggestion,
                startPosition = cursorPosition,
                cursorLine = cursorPosition.line,
                cursorColumn = cursorPosition.column,
                requestTimestamp = System.currentTimeMillis()
            )

            // Cache the result
            cache.put(cacheKey, suggestion)

            log.info("Suggestion generated successfully (${limitedSuggestion.lines().size} lines)")
            suggestion

        } catch (e: kotlinx.coroutines.CancellationException) {
            // Superseded by a newer request — propagate so the in-flight generation is cancelled
            // and frees the LLM run-loop thread instead of running to completion.
            throw e
        } catch (e: Exception) {
            log.error("Failed to get suggestion from LLM", e)
            null
        }
    }

    /**
     * Cancel the currently active suggestion request.
     */
    fun cancelActiveRequest() {
        activeRequest?.cancel()
        activeRequest = null
    }

    /**
     * Clear the suggestion cache.
     */
    fun clearCache() {
        cache.clear()
        log.debug("Suggestion cache cleared")
    }

    fun computeCacheKey(
        position: Position,
        fileContent: String,
        language: String
    ): String {
        // Simple cache key: combine position and surrounding context
        val contextWindow = fileContent.take(500)  // First 500 chars
        return "${position.line}:${position.column}:${language}:${contextWindow.hashCode()}"
    }

    /**
     * Extract context before and after cursor position.
     */
    private fun extractContext(
        fileContent: String,
        cursorPosition: Position,
        maxContextLines: Int = 30
    ): Pair<String, String> {
        val lines = fileContent.lines()

        // Calculate range
        val cursorLine = cursorPosition.line.coerceIn(0, lines.size - 1)
        val startLine = (cursorLine - maxContextLines).coerceAtLeast(0)
        val endLine = min(cursorLine + maxContextLines, lines.size)

        // Extract lines before cursor (up to cursor position on current line)
        val beforeLines = lines.subList(startLine, cursorLine + 1)
        val currentLineUpToCursor = if (beforeLines.isNotEmpty()) {
            val lastLine = beforeLines.last()
            lastLine.take(min(cursorPosition.column, lastLine.length))
        } else {
            ""
        }

        val contextBefore = if (beforeLines.size > 1) {
            beforeLines.dropLast(1).joinToString("\n") + "\n" + currentLineUpToCursor
        } else {
            currentLineUpToCursor
        }

        // Extract lines after cursor
        val afterLines = if (cursorLine < lines.size) {
            val currentLineAfterCursor = lines[cursorLine].drop(
                min(cursorPosition.column, lines[cursorLine].length)
            )
            val remainingLines = if (cursorLine + 1 < endLine) {
                lines.subList(cursorLine + 1, endLine)
            } else {
                emptyList()
            }

            if (currentLineAfterCursor.isNotEmpty()) {
                listOf(currentLineAfterCursor) + remainingLines
            } else {
                remainingLines
            }
        } else {
            emptyList()
        }

        val contextAfter = afterLines.joinToString("\n")

        return Pair(contextBefore, contextAfter)
    }

    /**
     * Build a code completion prompt optimized for inline suggestions.
     */
    private fun buildCompletionPrompt(
        contextBefore: String,
        contextAfter: String,
        language: String,
        cursorLine: Int,
        cursorColumn: Int
    ): String {
        // Raw code continuation: inference runs as plain completion (not chat), so the model
        // continues whatever the prompt ends with. Ending with the code up to the cursor makes it
        // continue code; a natural-language instruction here makes instruct models reply with
        // prose ("2 lines of code. Replace ...") that then gets inserted into the file.
        return contextBefore
    }

    /**
     * Clean the LLM response to extract just the code suggestion.
     */
    private fun cleanSuggestionText(response: String): String {
        var cleaned = response.trim()

        // Remove common LLM artifacts (code fences and leading labels)
        cleaned = cleaned
            .removePrefix("```" + editor.file?.extension)
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
            .removePrefix("Completion:")
            .removePrefix("Code:")
            .removePrefix("Here's the completion:")
            .removePrefix("Here is the completion:")
            .trim()

        // Safety net: a continuation prompt should yield code, but instruct models sometimes
        // still reply with prose (e.g. "2 lines of code. Replace ..."). Reject such responses
        // rather than inserting them into the file.
        if (looksLikeProse(cleaned)) {
            return ""
        }

        return cleaned
    }

    /**
     * Heuristic guard against non-code, assistant-style prose leaking into a suggestion.
     */
    private fun looksLikeProse(text: String): Boolean {
        val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: return false
        val proseOpener = Regex(
            "^(sure|okay|ok|here('?s| is)|i('| a|'ll| will| can)|the (code|following)|" +
                "to (complete|implement)|this (code|will)|\\d+ lines? of code|replace the)\\b",
            RegexOption.IGNORE_CASE
        )
        return proseOpener.containsMatchIn(firstLine)
    }

    /**
     * Limit suggestion to maximum number of lines.
     */
    private fun limitToMaxLines(text: String, maxLines: Int): String {
        val lines = text.lines()
        return if (lines.size > maxLines) {
            lines.take(maxLines).joinToString("\n")
        } else {
            text
        }
    }
}

/**
 * Interface for suggestion caching to support testing.
 */
interface SuggestionCache {
    fun get(key: String): SuggestionData?
    fun put(key: String, value: SuggestionData)
    fun remove(key: String)
    fun clear()
}

/**
 * Default implementation using Android's LruCache.
 * Cache size: 20 items (global constraint)
 */
class DefaultSuggestionCache : SuggestionCache {

    private val lruCache = LruCache<String, SuggestionData>(20)

    override fun get(key: String): SuggestionData? = lruCache.get(key)

    override fun put(key: String, value: SuggestionData) {
        lruCache.put(key, value)
    }

    override fun remove(key: String) {
        lruCache.remove(key)
    }

    override fun clear() {
        lruCache.evictAll()
    }
}
