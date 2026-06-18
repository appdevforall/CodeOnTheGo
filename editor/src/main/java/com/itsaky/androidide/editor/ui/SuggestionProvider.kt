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
 * Provides inline code suggestions from local LLM.
 *
 * Handles request management, caching, and cancellation.
 */
class SuggestionProvider(
    private val editor: IDEEditor,
    private val cache: SuggestionCache = DefaultSuggestionCache(),
    private val llmInference: LlmInferenceFunction? = null,
    private val llmModelCheck: LlmModelCheckFunction? = null
) {

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

            if (!llmModelCheck()) {
                log.debug("No model loaded, cannot provide suggestions")
                return null
            }

            // Extract context around cursor
            val (contextBefore, contextAfter) = extractContext(
                fileContent,
                cursorPosition,
                maxContextLines = 30
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

            // Request completion with timeout (10 seconds)
            val response = withTimeout(10_000) {
                llmInference(
                    prompt,
                    listOf("\n\n", "```", "<|eot_id|>"),  // Stop at blank line or code fence
                    false  // Don't clear KV cache for faster completions
                )
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
        return """Complete the code at the cursor position. Provide ONLY the next few lines that should be inserted at the cursor, without repeating existing code.

Language: $language
Line ${cursorLine + 1}, Column ${cursorColumn + 1}

Code before cursor:
```$language
$contextBefore
```

Code after cursor (if any):
```$language
$contextAfter
```

Instructions:
- Provide 1-5 lines of code completion
- Match the existing code style and indentation
- DO NOT repeat code that already exists
- DO NOT include explanations or comments about what you're doing
- Provide ONLY the code to insert at cursor position
- Stop after completing the current statement or block

Completion:"""
    }

    /**
     * Clean the LLM response to extract just the code suggestion.
     */
    private fun cleanSuggestionText(response: String): String {
        var cleaned = response.trim()

        // Remove common LLM artifacts
        cleaned = cleaned
            .removePrefix("```" + editor.file?.extension)
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        // Remove leading "Completion:" or similar labels
        cleaned = cleaned
            .removePrefix("Completion:")
            .removePrefix("Code:")
            .removePrefix("Here's the completion:")
            .removePrefix("Here is the completion:")
            .trim()

        // If the response starts with explanation text, try to extract just code
        if (cleaned.lines().any { it.contains("I") || it.contains("The code") || it.contains("This") }) {
            // Look for a code fence or indented block
            val codeStart = cleaned.indexOf("```")
            if (codeStart != -1) {
                val codeEnd = cleaned.indexOf("```", codeStart + 3)
                if (codeEnd != -1) {
                    cleaned = cleaned.substring(codeStart + 3, codeEnd).trim()
                }
            }
        }

        return cleaned
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
